// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.domain.carecircle.FollowedPatient as Following
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaregiverModeViewModelTest {

  private val anne = FollowedPatient("p-anne", "Anne", setOf(CarePermission.PLANNING))
  private val paul = FollowedPatient("p-paul", "Paul", setOf(CarePermission.EVENTS))

  /** "caregiver" signed in and following "patient", who shares Planning with them. */
  private val following = Following(CarePermission.PLANNING)
  private val patient = FollowedPatient("patient", "Anne", setOf(CarePermission.PLANNING))

  @Before
  fun setMain() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    // Anne and Paul also have the caregiver in their circle
    for (uid in listOf(anne.uid, paul.uid)) {
      following.circle.setMembers(
          uid,
          listOf(CareCircleMember(uid = "caregiver", firstName = "Sam")),
      )
    }
  }

  @After
  fun resetMain() {
    Dispatchers.resetMain()
  }

  private fun viewModel(savedState: SavedStateHandle = SavedStateHandle()) =
      CaregiverModeViewModel(savedState, following.auth, following.careAccess)

  @Test
  fun startsInPatientMode() {
    assertNull(viewModel().patient.value)
  }

  @Test
  fun choosingAPatient_showsTheirCare_andAnotherChoiceReplacesIt() {
    val vm = viewModel()
    vm.choose(anne)
    assertEquals(ChosenPatient("p-anne", "Anne"), vm.patient.value)

    vm.choose(paul)
    assertEquals(ChosenPatient("p-paul", "Paul"), vm.patient.value)
  }

  @Test
  fun theChoice_survivesAndroidStoppingTheApp() {
    val savedState = SavedStateHandle()
    viewModel(savedState).choose(anne)

    // A new instance from the same saved state, as when Android restores the app
    assertEquals(ChosenPatient("p-anne", "Anne"), viewModel(savedState).patient.value)
  }

  @Test
  fun leaving_returnsToPatientMode_alsoAfterARestore() {
    val savedState = SavedStateHandle()
    val vm = viewModel(savedState)
    vm.choose(anne)

    vm.leave()

    assertNull(vm.patient.value)
    assertNull(viewModel(savedState).patient.value)
  }

  @Test
  fun removedByTheChosenPatient_leavesCaregiverMode_untilHandled() {
    val savedState = SavedStateHandle()
    val vm = viewModel(savedState)
    vm.choose(patient)
    assertFalse(vm.lostAccess.value)

    following.removesTheCaregiver()

    assertTrue(vm.lostAccess.value)
    assertNull(vm.patient.value)
    assertNull(viewModel(savedState).patient.value)
    vm.accessLossHandled()
    assertFalse(vm.lostAccess.value)
  }

  @Test
  fun sharingLessOrSigningOut_isNotALostAccess() {
    val vm = viewModel()
    vm.choose(patient)

    // The screens show what isn't shared; sign-out takes the caregiver to sign in
    // Offline, a membership the server hasn't confirmed isn't a removal either (#204)
    val changes =
        listOf(
            { following.shares() },
            { following.isUnconfirmed() },
            { following.auth.signOut() },
        )
    for (change in changes) {
      change()
      assertFalse(vm.lostAccess.value)
    }
  }

  @Test
  fun accessThatCantBeRead_keepsCaregiverMode() {
    // E.g. the circle can't be read: the screens show their own error, nobody is sent away
    following.circle.observeError = IllegalStateException("offline")
    val vm = viewModel()
    vm.choose(patient)

    assertFalse(vm.lostAccess.value)
    assertEquals(ChosenPatient("patient", "Anne"), vm.patient.value)
  }

  @Test
  fun anAccessThatFailedOnce_stillLetsTheNextPatientsRemovalBeNoticed() {
    following.circle.observeError = IllegalStateException("offline")
    val vm = viewModel()
    vm.choose(anne)
    following.circle.observeError = null

    vm.choose(paul)
    following.circle.setMembers(paul.uid, emptyList())

    assertTrue(vm.lostAccess.value)
  }
}
