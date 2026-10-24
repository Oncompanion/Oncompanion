// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.RedeemResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why joining a care circle failed, so the screen can show the right message. */
enum class JoinCareCircleError {
  /** Unknown, mistyped, already used or expired invite code: they aren't told apart. */
  INVALID_CODE,
  /** Joining needs a connection, as only the server knows if the code is still valid. */
  OFFLINE,
  /** Nobody is signed in (e.g. the session expired): the user has to sign in again. */
  NOT_SIGNED_IN,
  /** Anything else. */
  FAILED,
}

/**
 * What the "Join a care circle" screen shows.
 *
 * @property code the invite code as typed (e.g. "abcde-fghjk")
 * @property isJoining the invite code is being checked on the server
 * @property joinedPatientFirstName set once the caregiver is in the patient's circle
 * @property wasAlreadyMember the caregiver was already in that circle before this code
 * @property error why the last try failed, until the user changes the code
 */
data class JoinCareCircleUiState(
    val code: String = "",
    val isJoining: Boolean = false,
    val joinedPatientFirstName: String? = null,
    val wasAlreadyMember: Boolean = false,
    val error: JoinCareCircleError? = null,
) {
  /** The caregiver is in the patient's circle: the screen shows the confirmation. */
  val hasJoined: Boolean
    get() = joinedPatientFirstName != null

  /** The code has as many letters and digits as an invite code, and nothing is in progress. */
  val canJoin: Boolean
    get() =
        CareCircleInvite.normalizeInviteCode(code).length == CareCircleInvite.INVITE_CODE_LENGTH &&
            !isJoining &&
            !hasJoined
}

/**
 * Lets a caregiver join a patient's care circle with the invite code the patient shared (US-15). A
 * wrong invite code keeps the caregiver on the screen to fix it.
 */
class JoinCareCircleViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(JoinCareCircleUiState())
  val uiState: StateFlow<JoinCareCircleUiState> = _uiState.asStateFlow()

  /**
   * Stores the code as typed, up to [MAX_CODE_INPUT_LENGTH] characters (room for a dash and
   * spaces). Ignored while joining and once joined. Clears the last error, which was about the old
   * code.
   */
  fun onCodeChange(value: String) {
    _uiState.update {
      if (it.isJoining || it.hasJoined) it
      else it.copy(code = value.take(MAX_CODE_INPUT_LENGTH), error = null)
    }
  }

  /**
   * Redeems the invite code for the signed-in caregiver, if [JoinCareCircleUiState.canJoin]. Being
   * in the circle already counts as joined, so the caregiver isn't stuck on the screen.
   */
  fun join() {
    val state = _uiState.value
    if (!state.canJoin) return
    val user = authRepository.currentUser
    if (user == null) {
      _uiState.update { it.copy(error = JoinCareCircleError.NOT_SIGNED_IN) }
      return
    }
    _uiState.update { it.copy(isJoining = true, error = null) }
    viewModelScope.launch {
      val result =
          try {
            inviteRepository.redeem(state.code, user.uid, user.email)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Joining a care circle failed", e)
            null
          }
      _uiState.update {
        when (result) {
          is RedeemResult.Joined ->
              it.copy(isJoining = false, joinedPatientFirstName = result.patientFirstName)
          is RedeemResult.AlreadyMember ->
              it.copy(
                  isJoining = false,
                  joinedPatientFirstName = result.patientFirstName,
                  wasAlreadyMember = true,
              )
          RedeemResult.InvalidOrUsed ->
              it.copy(isJoining = false, error = JoinCareCircleError.INVALID_CODE)
          RedeemResult.Offline -> it.copy(isJoining = false, error = JoinCareCircleError.OFFLINE)
          null -> it.copy(isJoining = false, error = JoinCareCircleError.FAILED)
        }
      }
    }
  }

  private companion object {
    const val TAG = "JoinCareCircleViewModel"

    /** An invite code is 10 characters, shown as "ABCDE-FGHJK": a few more allow pasting it. */
    const val MAX_CODE_INPUT_LENGTH = 16
  }
}
