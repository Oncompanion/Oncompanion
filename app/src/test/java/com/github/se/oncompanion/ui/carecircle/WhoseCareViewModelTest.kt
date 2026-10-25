// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhoseCareViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val caregiver = "caregiver"

  private lateinit var auth: FakeAuthRepository
  private lateinit var invites: FakeCareCircleInviteRepository
  private lateinit var circles: FakeCareCircleRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = caregiver) })
    runBlocking { auth.signInWithGoogle("token") }
    invites = FakeCareCircleInviteRepository()
    circles = FakeCareCircleRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** [patientUid]'s circle with the caregiver in it, sharing [sections]. */
  private fun follow(patientUid: String, firstName: String, vararg sections: CarePermission) {
    circles.setMembers(
        patientUid,
        listOf(
            CareCircleMember(uid = caregiver, firstName = "Sam", permissions = sections.toSet())
        ),
    )
    invites.setJoinedCircles(
        caregiver,
        invites.joinedCircles(caregiver) + JoinedCareCircle(patientUid, firstName),
    )
  }

  private fun viewModel(circleRepository: CareCircleRepository = circles) =
      WhoseCareViewModel(auth, invites, circleRepository)

  private val WhoseCareViewModel.state
    get() = uiState.value

  @Test
  fun loading_untilTheListArrives() = runTest {
    val vm = viewModel()
    assertTrue(vm.state.isLoading)
    advanceUntilIdle()
    assertFalse(vm.state.isLoading)
  }

  @Test
  fun lists_thePatients_withWhatTheyShare_sortedByFirstName() = runTest {
    follow("p-paul", "Paul", CarePermission.PLANNING, CarePermission.PRESCRIPTIONS)
    follow("p-anne", "Anne", CarePermission.PLANNING, CarePermission.SYMPTOMS)

    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(
        listOf(
            FollowedPatient(
                "p-anne",
                "Anne",
                setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
            ),
            FollowedPatient(
                "p-paul",
                "Paul",
                setOf(CarePermission.PLANNING, CarePermission.PRESCRIPTIONS),
            ),
        ),
        vm.state.patients,
    )
    assertFalse(vm.state.hasError)
  }

  @Test
  fun noCircle_isAnEmptyList() = runTest {
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(emptyList<FollowedPatient>(), vm.state.patients)
    assertFalse(vm.state.hasError)
  }

  @Test
  fun aPatientWhoRemovedTheCaregiver_isLeftOut() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    // Joined, but no member document anymore
    invites.setJoinedCircles(
        caregiver,
        invites.joinedCircles(caregiver) + JoinedCareCircle("p-gone", "Gone"),
    )

    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
  }

  @Test
  fun anAccessThatCantBeRead_leavesOnlyThatPatientOut() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    follow("p-paul", "Paul", CarePermission.SYMPTOMS)
    // The rules deny Paul's member document before his circle disappears from the list
    val denying =
        object : CareCircleRepository by circles {
          override fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?> =
              if (ownerUid == "p-paul") flow { throw IllegalStateException("denied") }
              else circles.observeMember(ownerUid, memberUid)
        }

    val vm = viewModel(denying)
    advanceUntilIdle()

    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
    assertFalse(vm.state.hasError)
  }

  @Test
  fun followsChanges_inTheSharedSections_andTheCircles() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    val vm = viewModel()
    advanceUntilIdle()

    // Anne shares symptoms too
    circles.setMembers(
        "p-anne",
        listOf(
            CareCircleMember(
                uid = caregiver,
                firstName = "Sam",
                permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
            )
        ),
    )
    advanceUntilIdle()
    assertEquals(
        setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
        vm.state.patients.single().sharedSections,
    )

    // Then removes the caregiver
    invites.setJoinedCircles(caregiver, emptyList())
    advanceUntilIdle()
    assertTrue(vm.state.patients.isEmpty())
  }

  @Test
  fun theCirclesCantBeRead_showsAnError_andRetryLoadsThem() = runTest {
    follow("p-anne", "Anne", CarePermission.PLANNING)
    invites.observeError = IllegalStateException("offline")
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.state.hasError)
    assertFalse(vm.state.isLoading)

    invites.observeError = null
    vm.load()
    advanceUntilIdle()
    assertFalse(vm.state.hasError)
    assertEquals(listOf("p-anne"), vm.state.patients.map { it.uid })
  }

  @Test
  fun nobodySignedIn_showsAnError() = runTest {
    auth.signOut()
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.state.hasError)
    assertFalse(vm.state.isLoading)
  }
}
