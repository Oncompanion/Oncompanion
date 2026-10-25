// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
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
 * @property hasError true if it couldn't be loaded
 */
data class WhoseCareUiState(
    val patients: List<FollowedPatient> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
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
      _uiState.update { it.copy(isLoading = false, hasError = true) }
      return
    }
    observation = viewModelScope.launch {
      inviteRepository
          .observeJoinedCircles(uid)
          .flatMapLatest { circles -> followed(circles, uid) }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to load the followed patients", e)
            _uiState.update { it.copy(isLoading = false, hasError = true) }
          }
          .collect { patients ->
            _uiState.update { WhoseCareUiState(patients = patients, isLoading = false) }
          }
    }
  }

  /** Each circle with what its patient shares with [caregiverUid], live, sorted by first name. */
  private fun followed(circles: List<JoinedCareCircle>, caregiverUid: String) =
      if (circles.isEmpty()) flowOf(emptyList())
      else
          combine(circles.map { patientOf(it, caregiverUid) }) { patients ->
            patients.filterNotNull().sortedBy { it.firstName.lowercase() }
          }

  /**
   * The patient of [circle], or null once they removed the caregiver (their member document is
   * gone) or if it can't be read: only that patient is left out, not the whole list.
   */
  private fun patientOf(circle: JoinedCareCircle, caregiverUid: String): Flow<FollowedPatient?> =
      circleRepository
          .observeMember(circle.patientUid, caregiverUid)
          .map { member ->
            member?.let {
              FollowedPatient(circle.patientUid, circle.patientFirstName, it.permissions)
            }
          }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't read the access to ${circle.patientUid}; left out", e)
            emit(null)
          }

  private companion object {
    const val TAG = "WhoseCareViewModel"
  }
}
