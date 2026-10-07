package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.ui.navigation.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** What the care circle member screen shows. */
sealed interface CareCircleMemberUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CareCircleMemberUiState

  /** The member's details. */
  data class Member(val member: CareCircleMember) : CareCircleMemberUiState

  /**
   * The member isn't in the circle (anymore): they were removed, possibly from another device, or
   * their document has no first name. Retrying wouldn't help.
   */
  data object NotFound : CareCircleMemberUiState

  /**
   * The member couldn't be read: nobody is signed in, the screen was opened without a member, or
   * Firestore refused the read (e.g. security rules). Being offline is not an error: the local
   * cache answers.
   */
  data object Error : CareCircleMemberUiState
}

/**
 * Shows one member of the signed-in user's care circle (US-15). The member's uid is the
 * [Screen.CARE_CIRCLE_MEMBER_ARG] navigation argument, read from [savedStateHandle].
 */
class CareCircleMemberViewModel(
    savedStateHandle: SavedStateHandle,
    private val careCircleRepository: CareCircleRepository = CareCircleRepositoryFirestore(),
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
) : ViewModel() {

  private val memberUid: String? = savedStateHandle[Screen.CARE_CIRCLE_MEMBER_ARG]

  private val _uiState = MutableStateFlow<CareCircleMemberUiState>(CareCircleMemberUiState.Loading)
  val uiState: StateFlow<CareCircleMemberUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  init {
    observeMember()
  }

  /** Reads the member again after an [CareCircleMemberUiState.Error]. */
  fun retry() {
    observeMember()
  }

  private fun observeMember() {
    // Never two listeners updating the state
    observeJob?.cancel()
    _uiState.value = CareCircleMemberUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val ownerUid = authRepository.currentUser?.uid
        if (ownerUid == null || memberUid.isNullOrBlank()) {
          _uiState.value = CareCircleMemberUiState.Error
          return@launch
        }
        careCircleRepository
            .observeMember(ownerUid, memberUid)
            .catch { _uiState.value = CareCircleMemberUiState.Error }
            .collect { member ->
              _uiState.value =
                  if (member == null) CareCircleMemberUiState.NotFound
                  else CareCircleMemberUiState.Member(member)
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CareCircleMemberUiState.Error
      }
    }
  }
}
