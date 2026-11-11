// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareSection
import com.github.se.oncompanion.domain.carecircle.ObserveCareSection
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.ui.common.DeleteConfirmation
import com.github.se.oncompanion.ui.common.DeletePhase
import com.github.se.oncompanion.ui.common.HasDeletePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Exclusive states prevent old appointment content from remaining visible after a read failure. */
sealed interface AppointmentDetailUiState : HasDeletePhase {
  override val delete: DeletePhase
    get() = DeletePhase.IDLE

  override val canDelete: Boolean
    get() = this is Loaded && isOwn && !delete.isUnderWay

  /** Waiting for this patient's locally available appointment. */
  data object Loading : AppointmentDetailUiState

  /**
   * All recorded patient fields are available for display.
   *
   * @property isOwn whether it is the user's own appointment: a caregiver only reads the patient's
   * @property addedByName who added it, if a caregiver did (US-41): for the patient, the name they
   *   gave that member in their circle; otherwise the name stored with it
   */
  data class Loaded(
      val appointment: Appointment,
      override val delete: DeletePhase = DeletePhase.IDLE,
      val isOwn: Boolean = true,
      val addedByName: String? = appointment.addedByName,
  ) : AppointmentDetailUiState

  /** In caregiver mode, the patient doesn't share their planning. */
  data object NotShared : AppointmentDetailUiState

  /** No readable local appointment, which does not confirm server-side deletion. */
  data object Unavailable : AppointmentDetailUiState

  /** The observation failed and can be restarted explicitly. */
  data object Error : AppointmentDetailUiState
}

/**
 * Observes one appointment while ensuring account changes clear the previous patient's data. In
 * caregiver mode (US-20), the appointment is the followed patient's, read-only, when they share
 * their planning.
 *
 * @param patientUid in caregiver mode, the patient whose appointment is shown; null for the user's
 *   own
 * @param careCircleRepository the patient's circle, for the name of the caregiver who added it
 */
class AppointmentDetailViewModel(
    private val appointmentId: String,
    private val repository: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val patientUid: String? = null,
    private val resolveSection: ResolveSectionAccess =
        ResolveSectionAccess(ResolveCareCircleAccess(auth = authRepository), authRepository),
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
) : ViewModel() {
  private val state = MutableStateFlow<AppointmentDetailUiState>(AppointmentDetailUiState.Loading)
  /** Current read state, cleared when its owner changes. */
  val uiState = state.asStateFlow()
  private var observation: Job? = null

  private var authentication: Job? = null
  private var owner: String? = null
  private var session = 0L
  private val deletion =
      DeleteConfirmation(
          state,
          viewModelScope,
          "AppointmentDetail",
          "appointment $appointmentId",
          withDelete = { current, delete ->
            if (current is AppointmentDetailUiState.Loaded) current.copy(delete = delete)
            else current
          },
      )

  /** Opens the shared confirmation without changing storage. */
  fun requestDelete() = deletion.request()

  /** Dismisses confirmation, unless the deletion is already being submitted. */
  fun cancelDelete() = deletion.cancel()

  /** Queues deletion for the displayed owner, suppressing results from replaced sessions. */
  fun confirmDelete() {
    val uid = owner ?: return
    val generation = session
    deletion.confirm(
        signedInUid = { uid.takeIf { isCurrentSession(uid, generation) } },
        stopObserving = { observation?.cancel() },
        delete = {
          // The shared helper must not apply an old completion to a new patient's Loaded state.
          try {
            checkSession(uid, generation)
            repository.deleteAppointment(uid, appointmentId)
          } catch (error: Exception) {
            checkSession(uid, generation)
            throw error
          }
          checkSession(uid, generation)
        },
        onFailure = { if (isCurrentSession(uid, generation)) observe(uid, retainLoaded = true) },
    )
  }

  /** Whether completion still belongs to the patient whose detail is on screen. */
  fun canLeaveAfterDeletion(): Boolean =
      state.value.delete == DeletePhase.DELETED &&
          owner?.let { isCurrentSession(it, session) } == true

  private fun isCurrentSession(uid: String, generation: Long): Boolean =
      generation == session &&
          owner == uid &&
          runCatching { authRepository.currentUser?.uid }.getOrNull() == uid

  private suspend fun checkSession(uid: String, generation: Long) {
    currentCoroutineContext().ensureActive()
    if (!isCurrentSession(uid, generation))
        throw CancellationException("Appointment session changed")
  }

  init {
    if (patientUid == null) observeAuthentication() else followPatient(patientUid)
  }

  /**
   * In caregiver mode, reads the patient's appointment while they share their planning. Nothing can
   * be deleted, so the session checks of the patient's own detail aren't needed: an account or
   * access change restarts the read from [AppointmentDetailUiState.Loading], and a read error
   * doesn't stop following the access (e.g. "not shared" once the patient stops sharing).
   */
  private fun followPatient(patientUid: String) {
    authentication?.cancel()
    authentication = viewModelScope.launch {
      ObserveCareSection(resolveSection)(CarePermission.PLANNING, patientUid) { ownerUid ->
            repository.observeAppointment(ownerUid, appointmentId)
          }
          .collect { state.value = patientAppointment(it) }
    }
  }

  /** What the caregiver sees for [section]: the patient's appointment, or why it isn't shown. */
  private fun patientAppointment(section: CareSection<Appointment?>): AppointmentDetailUiState =
      when (section) {
        is CareSection.Shown ->
            section.data?.let { AppointmentDetailUiState.Loaded(it, isOwn = false) }
                ?: AppointmentDetailUiState.Unavailable
        CareSection.NotShared -> AppointmentDetailUiState.NotShared
        is CareSection.Loading -> AppointmentDetailUiState.Loading
        CareSection.NoAccess -> AppointmentDetailUiState.Unavailable
        is CareSection.Failed -> AppointmentDetailUiState.Error
      }

  private fun observeAuthentication() {
    authentication?.cancel()
    authentication = viewModelScope.launch {
      flow { emitAll(authRepository.observeCurrentUser()) }
          .map { it?.uid }
          .distinctUntilChanged()
          .catch {
            observation?.cancel()
            owner = null
            session++
            state.value = AppointmentDetailUiState.Error
          }
          .collect { observe(it) }
    }
  }

  /** Restarts a failed or unavailable observation without keeping duplicate listeners. */
  fun retry() {
    if (state.value.delete.isUnderWay) return
    if (patientUid != null) return followPatient(patientUid)
    observe(runCatching { authRepository.currentUser?.uid }.getOrNull())
    observeAuthentication()
  }

  private fun observe(uid: String?, retainLoaded: Boolean = false) {
    observation?.cancel()
    if (!retainLoaded) {
      owner = uid
      session++
      state.value =
          if (uid == null) AppointmentDetailUiState.Unavailable
          else AppointmentDetailUiState.Loading
    }
    if (uid == null) return
    observation = viewModelScope.launch {
      // Late emissions belonging to a replaced session must never repopulate its private data.
      // An appointment a caregiver added shows the name the patient gave them (a rename shows)
      flow { emitAll(repository.observeAppointment(uid, appointmentId)) }
          .combine(careCircleRepository.observeMemberNames(uid)) { appointment, names ->
            appointment?.let {
              AppointmentDetailUiState.Loaded(
                  it,
                  state.value.delete.afterRead(exists = true),
                  addedByName = addedByName(it.addedBy, it.addedByName, names),
              )
            } ?: AppointmentDetailUiState.Unavailable
          }
          .catch { emit(AppointmentDetailUiState.Error) }
          .collect {
            if (runCatching { authRepository.currentUser?.uid }.getOrNull() == uid) state.value = it
          }
    }
  }
}
