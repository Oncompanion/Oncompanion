// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks whose planning [ObserveCarePlanning] shows: a patient's own, the planning a patient shares
 * with a caregiver, or why there is none. alice is a patient, bob a caregiver in her circle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObserveCarePlanningTest {

  private val auth = FakeAuthRepository { AuthUser(uid = it) }
  private val circle = FakeCareCircleRepository()
  private val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile("alice", Role.PATIENT, firstName = "Alice"))
        seed(UserProfile("bob", Role.CAREGIVER, firstName = "Bob"))
      }
  private val planning = FakePlanningRepository()
  private val observe =
      ObserveCarePlanning(
          ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, profiles), auth),
          planning,
          circle,
      )

  private val day = LocalDate.of(2026, 11, 4)
  private val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
  private val chemo =
      PlanningItem(
          PlanningSource.Appointment("chemo"),
          PlanningTiming.Timed(range.startInclusive.plusSeconds(3600)),
          "Chemotherapy",
      )

  private fun bobSees(vararg sections: CarePermission, canAdd: Set<CarePermission> = emptySet()) =
      circle.setMembers(
          "alice",
          listOf(
              CareCircleMember(
                  uid = "bob",
                  firstName = "Bob",
                  permissions = sections.toSet(),
                  canAdd = canAdd,
              )
          ),
      )

  /** Collects the planning of [patientUid] in the background and returns what it emitted so far. */
  private fun TestScope.collect(patientUid: String?): MutableList<CarePlanning> {
    val emissions = mutableListOf<CarePlanning>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      observe(patientUid, range).collect { emissions += it }
    }
    runCurrent()
    return emissions
  }

  @Test
  fun patient_seesTheirOwnPlanning() = runTest {
    planning.items.value = listOf(chemo)
    auth.signInWithGoogle("alice")

    val emissions = collect(null)

    // The patient can always add to their own planning
    assertEquals(CarePlanning.Shown(listOf(chemo), canAdd = true), emissions.last())
    assertEquals(listOf("alice"), planning.owners)
  }

  @Test
  fun caregiver_seesThePlanningThePatientShares() = runTest {
    planning.items.value = listOf(chemo)
    bobSees(CarePermission.PLANNING)
    auth.signInWithGoogle("bob")

    val emissions = collect("alice")

    assertEquals(CarePlanning.Shown(listOf(chemo)), emissions.last())
    assertEquals(listOf("alice"), planning.owners)
  }

  @Test
  fun caregiver_planningNotShared_isNotSharedAndReadsNothing() = runTest {
    planning.items.value = listOf(chemo)
    bobSees(CarePermission.SYMPTOMS)
    auth.signInWithGoogle("bob")

    val emissions = collect("alice")

    assertEquals(listOf<CarePlanning>(CarePlanning.NotShared), emissions)
    assertTrue(planning.owners.isEmpty())
  }

  @Test
  fun sharingTurnedOff_hidesThePlanningLive() = runTest {
    planning.items.value = listOf(chemo)
    bobSees(CarePermission.PLANNING)
    auth.signInWithGoogle("bob")
    val emissions = collect("alice")

    bobSees()
    runCurrent()

    assertEquals(CarePlanning.NotShared, emissions.last())
  }

  @Test
  fun signedOutOrNotInTheCircle_hasNoAccess() = runTest {
    val signedOut = collect(null)
    auth.signInWithGoogle("bob")
    val notMember = collect("alice")

    assertEquals(CarePlanning.NoAccess, signedOut.first())
    assertEquals(CarePlanning.NoAccess, notMember.last())
    // Never alice's planning, which bob isn't allowed to read
    assertTrue("alice" !in planning.owners)
  }

  @Test
  fun accessNotConfirmed_isLoading() = runTest {
    bobSees(CarePermission.PLANNING)
    circle.unconfirmedMembers = setOf("alice" to "bob")
    auth.signInWithGoogle("bob")

    assertEquals(listOf<CarePlanning>(CarePlanning.Loading), collect("alice"))
  }

  @Test
  fun accountChange_startsOverFromLoading_beforeTheNextPersonsPlanning() = runTest {
    planning.items.value = listOf(chemo)
    auth.signInWithGoogle("alice")
    val emissions = collect(null)
    emissions.clear()

    auth.signInWithGoogle("bob")
    runCurrent()

    // Nothing of alice's shows before bob's own planning
    assertEquals(CarePlanning.Loading, emissions.first())
    assertEquals(listOf("alice", "bob"), planning.owners)
  }

  @Test
  fun ownPlanning_needsNoProfile() = runTest {
    planning.items.value = listOf(chemo)
    auth.signInWithGoogle("nobody")

    assertEquals(CarePlanning.Shown(listOf(chemo), canAdd = true), collect(null).last())
  }

  @Test
  fun planningReadFailure_isFailed_andTheNextAccountStillLoads() = runTest {
    planning.fail = true
    bobSees(CarePermission.PLANNING)
    auth.signInWithGoogle("alice")
    val emissions = collect(null)
    assertEquals(CarePlanning.Failed, emissions.last())

    planning.fail = false
    planning.items.value = listOf(chemo)
    auth.signInWithGoogle("bob")
    val bobs = collect("alice")

    assertEquals(CarePlanning.Shown(listOf(chemo)), bobs.last())
  }

  @Test
  fun caregiver_getsAdd_onlyWhenThePatientLetsThemAddAppointments() = runTest {
    planning.items.value = listOf(chemo)
    auth.signInWithGoogle("bob")
    val cases =
        listOf(
            emptySet<CarePermission>() to false,
            setOf(CarePermission.SYMPTOMS) to false,
            setOf(CarePermission.PLANNING) to true,
        )
    for ((canAdd, expected) in cases) {
      bobSees(CarePermission.PLANNING, CarePermission.SYMPTOMS, canAdd = canAdd)
      assertEquals(
          "$canAdd",
          CarePlanning.Shown(listOf(chemo), canAdd = expected),
          collect("alice").last(),
      )
    }
  }

  @Test
  fun addedBy_isTheNameThePatientGaveTheMember_whileTheCaregiverKeepsTheStoredOne() = runTest {
    val added = chemo.copy(addedBy = "bob", addedByName = "Bob")
    planning.items.value = listOf(added)
    bobSees(CarePermission.PLANNING)
    // alice calls bob by another name in her circle
    circle.setMembers("alice", listOf(circle.members("alice").single().copy(firstName = "Bobby")))

    auth.signInWithGoogle("alice")
    assertEquals(
        CarePlanning.Shown(listOf(added.copy(addedByName = "Bobby")), canAdd = true),
        collect(null).last(),
    )

    auth.signInWithGoogle("bob")
    assertEquals(CarePlanning.Shown(listOf(added)), collect("alice").last())
  }

  @Test
  fun addedBy_fallsBackToTheStoredName_onceTheMemberIsRemoved() = runTest {
    val added = chemo.copy(addedBy = "bob", addedByName = "Bob")
    planning.items.value = listOf(added)
    auth.signInWithGoogle("alice")

    assertEquals(CarePlanning.Shown(listOf(added), canAdd = true), collect(null).last())
  }

  @Test
  fun ownPlanning_neverWaitsForTheCircle() = runTest {
    // Offline, a circle never read before may not answer
    circle.membersNeverAnswer = true
    val observe =
        ObserveCarePlanning(
            ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, profiles), auth),
            planning,
            circle,
        )
    val added = chemo.copy(addedBy = "bob", addedByName = "Bob")
    planning.items.value = listOf(added)
    auth.signInWithGoogle("alice")
    val emissions = mutableListOf<CarePlanning>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      observe(null, range).collect { emissions += it }
    }
    runCurrent()

    assertEquals(CarePlanning.Shown(listOf(added), canAdd = true), emissions.last())
  }
}
