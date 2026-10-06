package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** What the care circle screen shows. */
sealed interface CareCircleUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CareCircleUiState

  /** Nobody in the circle yet. */
  data object Empty : CareCircleUiState

  /** The members, sorted by name. Never empty. */
  data class Members(val members: List<CareCircleMember>) : CareCircleUiState

  /**
   * The circle couldn't be read: nobody is signed in, or Firestore refused the read (e.g. security
   * rules). Being offline is not an error: the local cache answers.
   */
  data object Error : CareCircleUiState
}

/** Lists the members of the signed-in user's care circle (US-13). */
class CareCircleViewModel(
    private val careCircleRepository: CareCircleRepository = CareCircleRepositoryFirestore(),
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
) : ViewModel() {

  private val _uiState = MutableStateFlow<CareCircleUiState>(CareCircleUiState.Loading)
  val uiState: StateFlow<CareCircleUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  init {
    observeMembers()
  }

  /** Reads the circle again after an [CareCircleUiState.Error]. */
  fun retry() {
    observeMembers()
  }

  private fun observeMembers() {
    observeJob?.cancel()
    _uiState.value = CareCircleUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val uid = authRepository.currentUser?.uid
        if (uid == null) {
          _uiState.value = CareCircleUiState.Error
          return@launch
        }
        careCircleRepository
            .observeMembers(uid)
            .catch { _uiState.value = CareCircleUiState.Error }
            .collect { members ->
              _uiState.value =
                  if (members.isEmpty()) CareCircleUiState.Empty
                  else CareCircleUiState.Members(members)
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CareCircleUiState.Error
      }
    }
  }
}
