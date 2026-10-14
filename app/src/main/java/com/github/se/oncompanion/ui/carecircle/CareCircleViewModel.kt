// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the care circle screen shows. */
sealed interface CareCircleUiState {
  /** Waiting for the first result (from the local cache or the server). */
  data object Loading : CareCircleUiState

  /** Nobody in the circle yet, and nobody invited. */
  data object Empty : CareCircleUiState

  /**
   * The circle, in two sections each sorted by name: the people who [joined], and the invitations
   * still [pending]. One of the two may be empty, never both.
   */
  data class CareCircleMembers(
      val joined: List<CareCircleEntry.Joined>,
      val pending: List<CareCircleEntry.Pending>,
  ) : CareCircleUiState

  /**
   * The circle couldn't be read: nobody is signed in, or Firestore refused the read (e.g. security
   * rules). Being offline is not an error: the local cache answers.
   */
  data object Error : CareCircleUiState
}

/**
 * Lists the signed-in user's care circle (US-13): its members and the invitations not used yet.
 *
 * @param clock tells whether an invitation has expired; tests pass one they control
 */
class CareCircleViewModel(
    private val careCircleRepository: CareCircleRepository =
        CareCircleRepositoryProvider.repository,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {

  private val _uiState = MutableStateFlow<CareCircleUiState>(CareCircleUiState.Loading)
  val uiState: StateFlow<CareCircleUiState> = _uiState.asStateFlow()

  private var observeJob: Job? = null

  /** Bumped to check expiry again without new data from the repositories. */
  private val expiryChecks = MutableStateFlow(0)

  init {
    observeCircle()
  }

  /**
   * Checks again which invitations have expired. The screen calls it when it resumes: the timer
   * that marks invitations expired doesn't run while the phone sleeps, and the user may change the
   * clock.
   */
  fun refreshExpiry() {
    expiryChecks.update { it + 1 }
  }

  /** Reads the circle again after an [CareCircleUiState.Error]. */
  fun retry() {
    observeCircle()
  }

  private fun observeCircle() {
    observeJob?.cancel()
    _uiState.value = CareCircleUiState.Loading
    observeJob = viewModelScope.launch {
      try {
        val uid = authRepository.currentUser?.uid
        if (uid == null) {
          _uiState.value = CareCircleUiState.Error
          return@launch
        }
        // If either list fails, the whole screen is an error: showing only the members would
        // quietly hide the people the patient invited
        combine(
                careCircleRepository.observeMembers(uid),
                inviteRepository.observePendingInvites(uid),
                expiryChecks,
            ) { members, invites, _ ->
              members to invites
            }
            .catch { _uiState.value = CareCircleUiState.Error }
            // New data or a refresh cancels the wait below and starts again from the new lists
            .collectLatest { (members, invites) ->
              while (true) {
                val now = clock.instant()
                _uiState.value = toUiState(members, invites, now)
                // Show the list again when the next invitation expires, so it never stays
                // "pending" after its validity ends while the screen is open
                val nextExpiry =
                    invites.mapNotNull { it.expiresAt }.filter { it > now }.minOrNull() ?: break
                // Rounded up, so it wakes up at or after the expiry, never just before
                delay(Duration.between(now, nextExpiry).toMillis() + 1)
              }
            }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CareCircleUiState.Error
      }
    }
  }

  private fun toUiState(
      members: List<CareCircleMember>,
      invites: List<CareCircleInvite>,
      now: Instant,
  ): CareCircleUiState =
      if (members.isEmpty() && invites.isEmpty()) CareCircleUiState.Empty
      else
          CareCircleUiState.CareCircleMembers(
              joined = members.map { CareCircleEntry.Joined(it) },
              pending = invites.map { CareCircleEntry.Pending(it, it.isExpired(now)) },
          )
}
