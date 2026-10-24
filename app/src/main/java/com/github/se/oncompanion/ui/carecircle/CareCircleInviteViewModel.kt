// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.ui.navigation.Screen
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the invite code screen shows. */
sealed interface CareCircleInviteUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CareCircleInviteUiState

  /**
   * The invitation, waiting for its invite code to be used.
   *
   * @property expired whether the invite code can no longer be used, as of the last check
   */
  data class Invite(val invite: CareCircleInvite, val expired: Boolean) : CareCircleInviteUiState

  /** The person used the invite code: [member] is now in the circle. */
  data class Joined(val member: CareCircleMember) : CareCircleInviteUiState

  /**
   * The invitation is gone and nobody joined with it: it was cancelled, possibly from another
   * device, or never existed. Retrying wouldn't help.
   */
  data object NotFound : CareCircleInviteUiState

  /**
   * The invitation couldn't be read: nobody is signed in, the screen was opened without an invite
   * code, or Firestore refused the read. Being offline is not an error: the local cache answers.
   */
  data object Error : CareCircleInviteUiState
}

/**
 * Shows one invitation of the signed-in patient's care circle (#150): its invite code, until the
 * person uses it. The invite code is the [Screen.CARE_CIRCLE_INVITE_ARG] navigation argument, read
 * from [savedStateHandle].
 *
 * A used invitation is deleted (see docs/architecture.md, "Care circle invitations"), so it looks
 * like a cancelled one. The circle's members tell them apart: someone who joined with this invite
 * code turns the screen into [CareCircleInviteUiState.Joined], live while it is open.
 *
 * @param clock tells whether the invitation has expired; tests pass one they control
 */
class CareCircleInviteViewModel(
    savedStateHandle: SavedStateHandle,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val inviteCode: String? = savedStateHandle[Screen.CARE_CIRCLE_INVITE_ARG]

  private val _uiState = MutableStateFlow<CareCircleInviteUiState>(CareCircleInviteUiState.Loading)
  val uiState: StateFlow<CareCircleInviteUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  /** Bumped to check expiry again without new data from the repositories. */
  private val expiryChecks = MutableStateFlow(0)

  init {
    observeInvite()
  }

  /**
   * Checks again whether the invitation has expired. The screen calls it when it resumes: the timer
   * doesn't run while the phone sleeps, and the user may change the clock.
   */
  fun refreshExpiry() {
    expiryChecks.update { it + 1 }
  }

  /** Reads the invitation again after an [CareCircleInviteUiState.Error]. */
  fun retry() {
    observeInvite()
  }

  private fun observeInvite() {
    observeJob?.cancel()
    _uiState.value = CareCircleInviteUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val ownerUid = authRepository.currentUser?.uid
        if (ownerUid == null || inviteCode.isNullOrBlank()) {
          _uiState.value = CareCircleInviteUiState.Error
          return@launch
        }
        // The members tell a used invitation (someone joined with it) from a cancelled one
        combine(
                inviteRepository.observeInvite(ownerUid, inviteCode),
                careCircleRepository.observeMembers(ownerUid),
                expiryChecks,
            ) { invite, members, _ ->
              invite to members.firstOrNull { it.inviteCode == inviteCode }
            }
            // New data or a refresh cancels the wait below and starts again
            .collectLatest { (invite, member) ->
              while (true) {
                val now = clock.instant()
                _uiState.value = toUiState(invite, member, now)
                // Show the invitation again when it expires, so it never looks valid after that
                val expiresAt = invite?.expiresAt?.takeIf { member == null && it > now } ?: break
                // Rounded up, so it wakes up at or after the expiry, never just before
                delay(Duration.between(now, expiresAt).toMillis() + 1)
              }
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available, or a listener refused by the rules
        _uiState.value = CareCircleInviteUiState.Error
      }
    }
  }

  private fun toUiState(
      invite: CareCircleInvite?,
      member: CareCircleMember?,
      now: Instant,
  ): CareCircleInviteUiState =
      when {
        member != null -> CareCircleInviteUiState.Joined(member)
        invite == null -> CareCircleInviteUiState.NotFound
        else -> CareCircleInviteUiState.Invite(invite, invite.isExpired(now))
      }
}
