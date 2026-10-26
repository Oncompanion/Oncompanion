// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Exclusive states prevent old appointment content from remaining visible after a read failure. */
sealed interface AppointmentDetailUiState {
  /** Waiting for this patient's locally available appointment. */
  data object Loading : AppointmentDetailUiState

  /** All recorded patient fields are available for display. */
  data class Loaded(val appointment: Appointment) : AppointmentDetailUiState

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
            state.value = AppointmentDetailUiState.Error
          }
          .collect { observe(it) }
    }
  }

  /** Restarts a failed or unavailable observation without keeping duplicate listeners. */
  fun retry() {
    observe(runCatching { authRepository.currentUser?.uid }.getOrNull())
    observeAuthentication()
  }

  private fun observe(uid: String?) {
    observation?.cancel()
    state.value =
        if (uid == null) AppointmentDetailUiState.Unavailable else AppointmentDetailUiState.Loading
    if (uid == null) return
    observation = viewModelScope.launch {
      // Late emissions belonging to a replaced session must never repopulate its private data.
      flow { emitAll(repository.observeAppointment(uid, appointmentId)) }
          .map<Appointment?, AppointmentDetailUiState> { appointment ->
            appointment?.let { AppointmentDetailUiState.Loaded(it) }
                ?: AppointmentDetailUiState.Unavailable
          }
          .catch { emit(AppointmentDetailUiState.Error) }
          .collect {
            if (runCatching { authRepository.currentUser?.uid }.getOrNull() == uid) state.value = it
          }
    }
  }
}
