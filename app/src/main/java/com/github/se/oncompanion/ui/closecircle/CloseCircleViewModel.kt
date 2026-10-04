package com.github.se.oncompanion.ui.closecircle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.closecircle.CloseCircleMember
import com.github.se.oncompanion.model.closecircle.CloseCircleRepository
import com.github.se.oncompanion.model.closecircle.CloseCircleRepositoryFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** What the close circle screen shows. */
sealed interface CloseCircleUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CloseCircleUiState

  /** Nobody in the circle yet. */
  data object Empty : CloseCircleUiState

  /** The members, sorted by name. Never empty. */
  data class Members(val members: List<CloseCircleMember>) : CloseCircleUiState

  /**
   * The circle couldn't be read: nobody is signed in, or Firestore refused the read (e.g. security
   * rules). Being offline is not an error: the local cache answers.
   */
  data object Error : CloseCircleUiState
}

/** Lists the members of the signed-in user's close circle (US-13). */
class CloseCircleViewModel(
    private val closeCircleRepository: CloseCircleRepository = CloseCircleRepositoryFirestore(),
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
) : ViewModel() {

  private val _uiState = MutableStateFlow<CloseCircleUiState>(CloseCircleUiState.Loading)
  val uiState: StateFlow<CloseCircleUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  init {
    observeMembers()
  }

  /** Reads the circle again after an [CloseCircleUiState.Error]. */
  fun retry() {
    observeMembers()
  }

  private fun observeMembers() {
    observeJob?.cancel()
    _uiState.value = CloseCircleUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val uid = authRepository.currentUser?.uid
        if (uid == null) {
          _uiState.value = CloseCircleUiState.Error
          return@launch
        }
        closeCircleRepository
            .observeMembers(uid)
            .catch { _uiState.value = CloseCircleUiState.Error }
            .collect { members ->
              _uiState.value =
                  if (members.isEmpty()) CloseCircleUiState.Empty
                  else CloseCircleUiState.Members(members)
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CloseCircleUiState.Error
      }
    }
  }
}
