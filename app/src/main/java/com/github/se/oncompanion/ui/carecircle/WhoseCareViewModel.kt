// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMembership
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A patient the caregiver follows.
 *
 * @property uid the patient's uid
 * @property firstName the patient's first name, as copied when the caregiver joined
 * @property sharedSections what the patient lets this caregiver see
 */
data class FollowedPatient(
    val uid: String,
    val firstName: String,
    val sharedSections: Set<CarePermission>,
)

/**
 * What "Whose care?" shows.
 *
 * @property patients the patients the caregiver follows, sorted by first name
 * @property isLoading true until the list arrives
 * @property hasError true if it couldn't be loaded; Retry can then work
 * @property isSignedOut true when nobody is signed in anymore (e.g. the session expired): Retry
 *   can't work, the caregiver has to sign in again
 * @property isConfirmed true when the server confirmed whether the caregiver is in each circle. An
 *   empty list only means they follow nobody once it is confirmed: right after joining again, the
 *   device can still remember the removal, a patient who can't be read isn't known either, and an
 *   empty list of circles from the cache may miss one joined on another phone
 */
data class WhoseCareUiState(
    val patients: List<FollowedPatient> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isSignedOut: Boolean = false,
    val isConfirmed: Boolean = false,
)

/**
 * Lists the patients a caregiver follows, with what each one shares with them (US-20), so they can
 * choose whose care to look at. The sections come from the caregiver's own member document in each
 * patient's circle, which a caregiver can read; a patient who removed them is left out.
 */
class WhoseCareViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
    private val circleRepository: CareCircleRepository = CareCircleRepositoryProvider.repository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(WhoseCareUiState())
  val uiState: StateFlow<WhoseCareUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    load()
  }

  /** Starts (or, after an error, restarts) observing the followed patients. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun load() {
    observation?.cancel()
    _uiState.update { it.copy(isLoading = true, hasError = false) }
    val uid = authRepository.currentUser?.uid
    if (uid == null) {
      // Still loading on screen until the caregiver is sent to sign in
      _uiState.update { it.copy(isSignedOut = true) }
      return
    }
    observation = viewModelScope.launch {
      inviteRepository
          .observeJoinedCircles(uid)
          // An empty list from the cache only: maybe a circle this phone doesn't know about yet.
          // Otherwise where the list came from doesn't matter: each patient answers for itself
          .map { it.circles to (it.circles.isEmpty() && it.isFromCache) }
          .distinctUntilChanged()
          .flatMapLatest { (circles, emptyFromCache) ->
            followed(circles, uid).map { answers -> answers to emptyFromCache }
          }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load the followed patients", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { (answers, emptyFromCache) ->
            _uiState.update {
              WhoseCareUiState(
                  patients =
                      answers
                          .filterIsInstance<Answer.Followed>()
                          .map { it.patient }
                          .sortedBy { it.firstName.lowercase() },
                  isLoading = false,
                  isConfirmed = !emptyFromCache && answers.none { it == Answer.Unknown },
              )
            }
          }
    }
  }

  /** What each circle's patient answers about [caregiverUid], live. */
  private fun followed(circles: List<JoinedCareCircle>, caregiverUid: String): Flow<List<Answer>> =
      if (circles.isEmpty()) flowOf(emptyList())
      else combine(circles.map { patientOf(it, caregiverUid) }) { it.toList() }

  /**
   * Whether [circle]'s patient still has the caregiver in their circle, with what they share. A
   * patient whose answer isn't known (not confirmed by the server, or unreadable) is left out of
   * the list, but doesn't count as a removal.
   */
  private fun patientOf(circle: JoinedCareCircle, caregiverUid: String): Flow<Answer> =
      circleRepository
          .observeMembership(circle.patientUid, caregiverUid)
          .map { membership ->
            when (membership) {
              is CareCircleMembership.Member ->
                  Answer.Followed(
                      FollowedPatient(
                          circle.patientUid,
                          circle.patientFirstName,
                          membership.member.permissions,
                      )
                  )
              CareCircleMembership.NotMember -> Answer.Removed
              CareCircleMembership.Unconfirmed -> Answer.Unknown
            }
          }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't read the access to ${circle.patientUid}; left out", e)
            emit(Answer.Unknown)
          }

  /** What one patient answers: still followed, removed the caregiver, or not known yet. */
  private sealed interface Answer {
    data class Followed(val patient: FollowedPatient) : Answer

    data object Removed : Answer

    data object Unknown : Answer
  }

  private companion object {
    const val TAG = "WhoseCareViewModel"
  }
}
