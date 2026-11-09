// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CaregiverName
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immediate failures that keep the draft available for correction or retry. */
enum class AppointmentFormError {
  NOT_SIGNED_IN,
  SAVE_FAILED,
}

/** Whether an existing appointment can be corrected using the shared form. */
enum class AppointmentFormLoad {
  READY,
  LOADING,
  UNAVAILABLE,
  NOT_SIGNED_IN,
  FAILED,
}

/**
 * Patient-entered values, including incomplete input that must never become a saved appointment.
 */
data class AppointmentFormUiState(
    val date: LocalDate,
    val zoneId: ZoneId,
    val title: String = "",
    val time: LocalTime? = null,
    val location: String = "",
    val type: AppointmentType = AppointmentType.OTHER,
    val notes: String = "",
    val titleTouched: Boolean = false,
    val chosenOffset: ZoneOffset? = null,
    val save: SavePhase = SavePhase.EDITING,
    val savedAt: Instant? = null,
    val completionHandled: Boolean = false,
    val error: AppointmentFormError? = null,
    val isEditing: Boolean = false,
    val load: AppointmentFormLoad = AppointmentFormLoad.READY,
) {
  /** Valid offsets distinguish ordinary times, missing spring times and repeated autumn times. */
  val offsets: List<ZoneOffset>
    get() = time?.let { zoneId.rules.getValidOffsets(date.atTime(it)) }.orEmpty()

  /** Only an explicitly resolved local time can be recorded as an instant. */
  val instant: Instant?
    get() = time?.let { value ->
      val offset = offsets.singleOrNull() ?: chosenOffset?.takeIf { it in offsets }
      offset?.let { date.atTime(value).toInstant(it) }
    }

  /** Saving and completed forms cannot be edited or submitted again. */
  val editable: Boolean
    get() = load == AppointmentFormLoad.READY && save == SavePhase.EDITING

  /** All patient input fits the existing appointment schema and resolves to a real time. */
  val canSave: Boolean
    get() =
        editable &&
            title.isNotBlank() &&
            title.trim().length <= Appointment.MAX_TITLE_LENGTH &&
            location.trim().length <= Appointment.MAX_LOCATION_LENGTH &&
            notes.trim().length <= Appointment.MAX_NOTES_LENGTH &&
            instant?.let {
              it >= Appointment.MIN_SCHEDULED_AT && it <= Appointment.MAX_SCHEDULED_AT
            } == true
}

/**
 * Creates or corrects a confirmed appointment locally; navigation remains outside this ViewModel.
 *
 * In caregiver mode (US-41), it adds the appointment to the planning of [patientUid], signed with
 * the caregiver's uid and first name; the rules only accept it if the patient lets them add
 * appointments. Only the patient edits an appointment.
 *
 * @param patientUid in caregiver mode, the patient whose planning gets the new appointment; null
 *   for the user's own
 * @param profileRepository the caregiver's profile, for the first name the appointment is signed
 *   with
 */
class AppointmentFormViewModel(
    private val initialDate: LocalDate,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val repository: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val appointmentId: String? = null,
    private val patientUid: String? = null,
    profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
) : ViewModel() {
  /** The first name a caregiver's appointment is signed with. */
  private val caregiverName = CaregiverName(profileRepository, authRepository)

  private val state = MutableStateFlow(emptyState(initialDate))
  /** Draft and load/save feedback, shared by creation and editing. */
  val uiState = state.asStateFlow()
  private var session = 0
  private var ownerUid = runCatching { authRepository.currentUser?.uid }.getOrNull()

  private var authentication: Job? = null
  private var observation: Job? = null
  private var original: Appointment? = null

  init {
    observeAuthentication()
  }

  private fun emptyState(date: LocalDate) =
      AppointmentFormUiState(
          date,
          zoneId,
          isEditing = appointmentId != null,
          load =
              if (appointmentId == null) AppointmentFormLoad.READY else AppointmentFormLoad.LOADING,
      )

  private fun observeAuthentication() {
    authentication?.cancel()
    authentication = viewModelScope.launch {
      flow { emitAll(authRepository.observeCurrentUser()) }
          .map { it?.uid }
          .distinctUntilChanged()
          .catch { resetOwner(null) }
          .collect { uid ->
            if (uid != ownerUid) resetOwner(uid)
            if (appointmentId != null) observeAppointment(uid)
          }
    }
  }

  private fun resetOwner(uid: String?) {
    session++
    ownerUid = uid
    observation?.cancel()
    original = null
    state.value =
        emptyState(initialDate)
            .copy(
                load =
                    if (appointmentId != null && uid == null) AppointmentFormLoad.NOT_SIGNED_IN
                    else emptyState(initialDate).load,
            )
  }

  /** Retries loading an existing appointment without replacing an edited draft. */
  fun load() {
    if (appointmentId == null || state.value.save != SavePhase.EDITING) return
    state.update { it.copy(load = AppointmentFormLoad.LOADING) }
    observeAuthentication()
  }

  private fun observeAppointment(uid: String?) {
    observation?.cancel()
    if (uid == null) {
      state.update { it.copy(load = AppointmentFormLoad.NOT_SIGNED_IN) }
      return
    }
    val attempt = session
    observation = viewModelScope.launch {
      flow { emitAll(repository.observeAppointment(uid, requireNotNull(appointmentId))) }
          .catch {
            if (isCurrentAttempt(uid, attempt))
                state.update { it.copy(load = AppointmentFormLoad.FAILED) }
          }
          .collect { saved -> if (isCurrentAttempt(uid, attempt)) show(saved) }
    }
  }

  private fun show(saved: Appointment?) {
    val draft = state.value
    if (draft.save == SavePhase.SAVED) return
    if (saved == null) {
      state.update { it.copy(load = AppointmentFormLoad.UNAVAILABLE) }
      return
    }
    if (draft.save == SavePhase.SAVING) return
    if (original == null || draft.matches(requireNotNull(original))) {
      val local = saved.scheduledAt.atZone(zoneId)
      original = saved
      state.value =
          AppointmentFormUiState(
              local.toLocalDate(),
              zoneId,
              title = saved.title,
              time = local.toLocalTime(),
              location = saved.location.orEmpty(),
              type = saved.type,
              notes = saved.notes.orEmpty(),
              chosenOffset = local.offset,
              isEditing = true,
          )
    } else state.update { it.copy(load = AppointmentFormLoad.READY) }
  }

  private fun AppointmentFormUiState.matches(saved: Appointment) =
      title == saved.title &&
          instant == saved.scheduledAt &&
          type == saved.type &&
          location == saved.location.orEmpty() &&
          notes == saved.notes.orEmpty()

  /** Keeps title input, marking a missing name after the patient edits it. */
  fun onTitleChange(value: String) = edit { it.copy(title = value, titleTouched = true) }

  /** Chooses a calendar date without silently resolving a repeated local time. */
  fun onDateChange(value: LocalDate) = edit {
    if (it.date == value) it else it.copy(date = value, chosenOffset = null)
  }

  /** Chooses an exact time, clearing any offset selected for an earlier value. */
  fun onTimeChange(value: LocalTime) = edit {
    if (it.time == value) it else it.copy(time = value, chosenOffset = null)
  }

  /** Keeps manually entered location text; overlong values remain visible for correction. */
  fun onLocationChange(value: String) = edit { it.copy(location = value) }

  /** Records the category selected by the patient. */
  fun onTypeChange(value: AppointmentType) = edit { it.copy(type = value) }

  /** Keeps optional patient notes without rewriting their content. */
  fun onNotesChange(value: String) = edit { it.copy(notes = value) }

  /** Resolves a repeated clock time using one of its actual offsets. */
  fun onOffsetChange(value: ZoneOffset) = edit {
    if (value in it.offsets) it.copy(chosenOffset = value) else it
  }

  /** Dismisses a displayed immediate-save error. */
  fun clearError() {
    state.update { it.copy(error = null) }
  }

  /** Marks navigation as delivered so recreation cannot return from the form twice. */
  fun onCompletionHandled(): Boolean {
    val saved = state.value
    val uid = ownerUid ?: return false
    if (saved.savedAt == null || saved.completionHandled || !isCurrentAttempt(uid, session))
        return false
    state.update { it.copy(completionHandled = true) }
    return true
  }

  /** Enqueues exactly one valid appointment, retaining the draft if an immediate write fails. */
  fun save() {
    val draft = state.value
    if (!draft.canSave) return
    val uid = validatedOwner() ?: return
    val attempt = session
    val appointment = draft.toAppointment()
    state.update { it.copy(save = SavePhase.SAVING, error = null) }
    viewModelScope.launch { writeAppointment(uid, appointment, attempt) }
  }

  // Authentication can change before observer delivery; reject the old patient's draft.
  private fun validatedOwner(): String? {
    val uid = runCatching { authRepository.currentUser?.uid }.getOrNull()
    if (uid != ownerUid) {
      resetOwner(uid)
      state.update { it.copy(error = AppointmentFormError.NOT_SIGNED_IN) }
      return null
    }
    if (uid == null) state.update { it.copy(error = AppointmentFormError.NOT_SIGNED_IN) }
    return uid
  }

  private fun AppointmentFormUiState.toAppointment() =
      Appointment(
          id = original?.id.orEmpty(),
          createdAt = original?.createdAt,
          title = title.trim(),
          scheduledAt = requireNotNull(instant),
          type = type,
          location = location.trim().ifEmpty { null },
          notes = notes.trim().ifEmpty { null },
      )

  private fun isCurrentAttempt(uid: String, attempt: Int) =
      attempt == session && runCatching { authRepository.currentUser?.uid }.getOrNull() == uid

  // Only the session that submitted the write may receive its completion.
  private suspend fun writeAppointment(uid: String, appointment: Appointment, attempt: Int) {
    try {
      if (!isCurrentAttempt(uid, attempt)) return
      if (appointmentId != null) repository.updateAppointment(uid, appointment)
      else if (patientUid == null) repository.addAppointment(uid, appointment)
      // A caregiver's appointment goes in the patient's planning, signed with who added it
      else {
        val signed = appointment.copy(addedBy = uid, addedByName = caregiverName(uid))
        // Reading the name can take a while: the account may have changed meanwhile
        currentCoroutineContext().ensureActive()
        if (!isCurrentAttempt(uid, attempt)) return
        repository.addAppointment(patientUid, signed)
      }
      if (isCurrentAttempt(uid, attempt))
          state.update {
            // Done only if the form still shows its appointment (e.g. it wasn't deleted meanwhile)
            val done = it.load == AppointmentFormLoad.READY
            it.copy(
                save = if (done) SavePhase.SAVED else SavePhase.EDITING,
                savedAt = appointment.scheduledAt.takeIf { done },
            )
          }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (isCurrentAttempt(uid, attempt))
          state.update {
            it.copy(save = SavePhase.EDITING, error = AppointmentFormError.SAVE_FAILED)
          }
    }
  }

  private fun edit(change: (AppointmentFormUiState) -> AppointmentFormUiState) {
    state.update { if (it.editable) change(it).copy(error = null) else it }
  }
}
