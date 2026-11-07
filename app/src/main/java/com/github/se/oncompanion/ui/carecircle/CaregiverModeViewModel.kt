// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.carecircle.CareAccess
import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** The patient a caregiver chose in "Whose care?", whose care the app then shows. */
data class ChosenPatient(val uid: String, val firstName: String)

/**
 * Caregiver mode (US-20): the patient chosen in "Whose care?", or `null` in patient mode. One
 * instance for the whole app, so every screen knows whose care it shows. Kept in [savedState], so
 * the choice survives Android stopping the app in the background.
 *
 * It also follows the caregiver's access to the chosen patient: once the patient removes them from
 * their circle, it leaves caregiver mode and raises [lostAccess], so the app stops showing a screen
 * that can no longer read anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverModeViewModel(
    private val savedState: SavedStateHandle,
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val resolveAccess: ResolveCareCircleAccess = ResolveCareCircleAccess(auth),
) : ViewModel() {

  private val _patient =
      MutableStateFlow(
          savedState.get<String>(UID)?.let { uid ->
            savedState.get<String>(FIRST_NAME)?.let { ChosenPatient(uid, it) }
          }
      )

  /** The chosen patient, or `null` in patient mode. */
  val patient: StateFlow<ChosenPatient?> = _patient.asStateFlow()

  private val _lostAccess = MutableStateFlow(false)

  /**
   * True once the chosen patient removed the caregiver from their circle, until the app has taken
   * them back to choosing whose care to follow ([accessLossHandled]).
   */
  val lostAccess: StateFlow<Boolean> = _lostAccess.asStateFlow()

  init {
    viewModelScope.launch {
      _patient
          .flatMapLatest { patient ->
            if (patient == null) emptyFlow() else resolveAccess(patient.uid)
          }
          // Signing out also denies the access, but that is not a removal: sign-out handles it
          .filter { it == CareAccess.Denied && auth.currentUser != null }
          .catch { e ->
            if (e is CancellationException) throw e
            // Without the access, the screens show their own error; nothing more to do here
            Log.w(
                TAG,
                "Couldn't follow the access to the chosen patient: ${e.javaClass.simpleName}",
            )
          }
          .collect {
            leave()
            _lostAccess.value = true
          }
    }
  }

  /** The app took the caregiver back to choosing whose care to follow after [lostAccess]. */
  fun accessLossHandled() {
    _lostAccess.value = false
  }

  /** Shows [patient]'s care from now on, until another patient is chosen or [leave]. */
  fun choose(patient: FollowedPatient) {
    savedState[UID] = patient.uid
    savedState[FIRST_NAME] = patient.firstName
    _patient.value = ChosenPatient(patient.uid, patient.firstName)
  }

  /** Back to patient mode, on signing out, so the next person to sign in starts afresh. */
  fun leave() {
    savedState.remove<String>(UID)
    savedState.remove<String>(FIRST_NAME)
    _patient.value = null
  }

  private companion object {
    const val TAG = "CaregiverModeViewModel"
    const val UID = "caregiverModePatientUid"
    const val FIRST_NAME = "caregiverModePatientFirstName"
  }
}
