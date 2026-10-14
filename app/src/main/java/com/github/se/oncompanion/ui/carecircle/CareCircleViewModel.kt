// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
  data class Members(
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
 * @param clock tells whether an invitation has expired; tests pass a fixed one
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

  init {
    observeCircle()
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
            ) { members, invites ->
              val now = clock.instant()
              if (members.isEmpty() && invites.isEmpty()) CareCircleUiState.Empty
              else
                  CareCircleUiState.Members(
                      joined = members.map { CareCircleEntry.Joined(it) },
                      pending = invites.map { CareCircleEntry.Pending(it, it.isExpired(now)) },
                  )
            }
            .catch { _uiState.value = CareCircleUiState.Error }
            .collect { _uiState.value = it }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // e.g. Firebase not available
        _uiState.value = CareCircleUiState.Error
      }
    }
  }
}
