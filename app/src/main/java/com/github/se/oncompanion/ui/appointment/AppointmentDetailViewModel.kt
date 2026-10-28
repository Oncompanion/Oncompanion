// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.ui.common.DeleteConfirmation
import com.github.se.oncompanion.ui.common.DeleteState
import com.github.se.oncompanion.ui.common.HasDeleteState
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
import kotlinx.coroutines.launch

/** Exclusive states prevent old appointment content from remaining visible after a read failure. */
sealed interface AppointmentDetailUiState : HasDeleteState {
  override val delete: DeleteState
    get() = DeleteState()

  override val canDelete: Boolean
    get() = this is Loaded && !delete.isDeleting && !delete.isDeleted

  /** Waiting for this patient's locally available appointment. */
  data object Loading : AppointmentDetailUiState

  /** All recorded patient fields are available for display. */
  data class Loaded(
      val appointment: Appointment,
      override val delete: DeleteState = DeleteState(),
  ) : AppointmentDetailUiState

  /** No readable local appointment, which does not confirm server-side deletion. */
  data object Unavailable : AppointmentDetailUiState

  /** The observation failed and can be restarted explicitly. */
  data object Error : AppointmentDetailUiState
}

/** Observes one appointment while ensuring account changes clear the previous patient's data. */
class AppointmentDetailViewModel(
    private val appointmentId: String,
    private val repository: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
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
      state.value.delete.isDeleted && owner?.let { isCurrentSession(it, session) } == true

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
    observeAuthentication()
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
    if (state.value.delete.isDeleting || state.value.delete.isDeleted) return
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
      flow { emitAll(repository.observeAppointment(uid, appointmentId)) }
          .map<Appointment?, AppointmentDetailUiState> { appointment ->
            appointment?.let {
              AppointmentDetailUiState.Loaded(it, state.value.delete.afterRead(exists = true))
            } ?: AppointmentDetailUiState.Unavailable
          }
          .catch { emit(AppointmentDetailUiState.Error) }
          .collect {
            if (runCatching { authRepository.currentUser?.uid }.getOrNull() == uid) state.value = it
          }
    }
  }
}
