// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Immediate failures that keep the draft available for correction or retry. */
enum class AppointmentFormError {
  NOT_SIGNED_IN,
  SAVE_FAILED,
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
    val isSaving: Boolean = false,
    val savedAt: Instant? = null,
    val completionHandled: Boolean = false,
    val error: AppointmentFormError? = null,
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
    get() = !isSaving && savedAt == null

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

/** Creates a confirmed appointment locally; navigation remains outside this ViewModel. */
class AppointmentFormViewModel(
    initialDate: LocalDate,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val repository: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
) : ViewModel() {
  private val state = MutableStateFlow(AppointmentFormUiState(initialDate, zoneId))
  val uiState = state.asStateFlow()
  private var session = 0
  private var ownerUid = runCatching { authRepository.currentUser?.uid }.getOrNull()

  init {
    // A changed patient must never inherit another patient's unsaved input or save completion.
    viewModelScope.launch {
      authRepository
          .observeCurrentUser()
          .catch { emit(null) }
          .map { it?.uid }
          .distinctUntilChanged()
          .collect { uid ->
            if (uid != ownerUid) {
              session++
              state.value = AppointmentFormUiState(initialDate, zoneId)
            }
            ownerUid = uid
          }
    }
  }

  /** Keeps title input, marking a missing name after the patient edits it. */
  fun onTitleChange(value: String) = edit { it.copy(title = value, titleTouched = true) }

  /** Chooses a calendar date without silently resolving a repeated local time. */
  fun onDateChange(value: LocalDate) = edit { it.copy(date = value, chosenOffset = null) }

  /** Chooses an exact time, clearing any offset selected for an earlier value. */
  fun onTimeChange(value: LocalTime) = edit { it.copy(time = value, chosenOffset = null) }

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
  fun onCompletionHandled() {
    state.update { it.copy(completionHandled = true) }
  }

  /** Enqueues exactly one valid appointment, retaining the draft if an immediate write fails. */
  fun save() {
    val draft = state.value
    if (!draft.canSave) return
    val uid = runCatching { authRepository.currentUser?.uid }.getOrNull()
    if (uid != ownerUid) {
      // Authentication can change before its flow is delivered; never submit the old draft.
      session++
      ownerUid = uid
      state.value =
          AppointmentFormUiState(draft.date, zoneId, error = AppointmentFormError.NOT_SIGNED_IN)
      return
    }
    if (uid == null) {
      state.update { it.copy(error = AppointmentFormError.NOT_SIGNED_IN) }
      return
    }
    val attempt = session
    val instant = requireNotNull(draft.instant)
    state.update { it.copy(isSaving = true, error = null) }
    viewModelScope.launch {
      try {
        if (attempt != session || authRepository.currentUser?.uid != uid) return@launch
        repository.addAppointment(
            uid,
            Appointment(
                title = draft.title.trim(),
                scheduledAt = instant,
                type = draft.type,
                location = draft.location.trim().ifEmpty { null },
                notes = draft.notes.trim().ifEmpty { null },
            ),
        )
        if (attempt == session && authRepository.currentUser?.uid == uid)
            state.update { it.copy(isSaving = false, savedAt = instant) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (attempt == session)
            state.update { it.copy(isSaving = false, error = AppointmentFormError.SAVE_FAILED) }
      }
    }
  }

  private fun edit(change: (AppointmentFormUiState) -> AppointmentFormUiState) {
    state.update { if (it.editable) change(it).copy(error = null) else it }
  }
}
