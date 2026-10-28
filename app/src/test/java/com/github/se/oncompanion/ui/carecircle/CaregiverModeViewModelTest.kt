// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.model.carecircle.CarePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaregiverModeViewModelTest {

  private val anne = FollowedPatient("p-anne", "Anne", setOf(CarePermission.PLANNING))
  private val paul = FollowedPatient("p-paul", "Paul", setOf(CarePermission.EVENTS))

  @Test
  fun startsInPatientMode() {
    assertNull(CaregiverModeViewModel(SavedStateHandle()).patient.value)
  }

  @Test
  fun choosingAPatient_showsTheirCare_andAnotherChoiceReplacesIt() {
    val vm = CaregiverModeViewModel(SavedStateHandle())
    vm.choose(anne)
    assertEquals(ChosenPatient("p-anne", "Anne"), vm.patient.value)

    vm.choose(paul)
    assertEquals(ChosenPatient("p-paul", "Paul"), vm.patient.value)
  }

  @Test
  fun theChoice_survivesAndroidStoppingTheApp() {
    val savedState = SavedStateHandle()
    CaregiverModeViewModel(savedState).choose(anne)

    // A new instance from the same saved state, as when Android restores the app
    assertEquals(ChosenPatient("p-anne", "Anne"), CaregiverModeViewModel(savedState).patient.value)
  }

  @Test
  fun leaving_returnsToPatientMode_alsoAfterARestore() {
    val savedState = SavedStateHandle()
    val vm = CaregiverModeViewModel(savedState)
    vm.choose(anne)

    vm.leave()

    assertNull(vm.patient.value)
    assertNull(CaregiverModeViewModel(savedState).patient.value)
  }
}
