// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The patient a caregiver chose in "Whose care?", whose care the app then shows. */
data class ChosenPatient(val uid: String, val firstName: String)

/**
 * Caregiver mode (US-20): the patient chosen in "Whose care?", or `null` in patient mode. One
 * instance for the whole app, so every screen knows whose care it shows. Kept in [savedState], so
 * the choice survives Android stopping the app in the background.
 */
class CaregiverModeViewModel(private val savedState: SavedStateHandle) : ViewModel() {

  private val _patient =
      MutableStateFlow(
          savedState.get<String>(UID)?.let { uid ->
            savedState.get<String>(FIRST_NAME)?.let { ChosenPatient(uid, it) }
          }
      )

  /** The chosen patient, or `null` in patient mode. */
  val patient: StateFlow<ChosenPatient?> = _patient.asStateFlow()

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
    const val UID = "caregiverModePatientUid"
    const val FIRST_NAME = "caregiverModePatientFirstName"
  }
}
