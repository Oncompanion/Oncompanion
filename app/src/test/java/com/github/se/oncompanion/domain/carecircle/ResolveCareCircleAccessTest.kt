// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks whose data and which sections [ResolveCareCircleAccess] grants, for a patient and for a
 * caregiver, that the role limits what each may ask for, and that it follows sign-in, permission
 * changes and removals live. alice is a patient; bob, carol and dave are caregivers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveCareCircleAccessTest {

  private val auth = FakeAuthRepository { AuthUser(uid = it) }
  private val circle = FakeCareCircleRepository()
  private val profiles =
      FakeUserProfileRepository().apply {
        seed(profile("alice", Role.PATIENT))
        listOf("bob", "carol", "dave").forEach { seed(profile(it, Role.CAREGIVER)) }
      }
  private val resolve = ResolveCareCircleAccess(auth, circle, profiles)

  private val everything = CarePermission.entries.toSet()

  private fun profile(uid: String, role: Role) =
      UserProfile(uid = uid, role = role, firstName = uid)

  private fun member(uid: String, vararg permissions: CarePermission) =
      CareCircleMember(
          uid = uid,
          firstName = uid.replaceFirstChar { it.uppercase() },
          permissions = permissions.toSet(),
      )

  /** Collects [resolve] for [patientUid] in the background and returns what it emitted so far. */
  private fun TestScope.collectAccess(patientUid: String?): MutableList<CareAccess> {
    val emissions = mutableListOf<CareAccess>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      resolve(patientUid).collect { emissions += it }
    }
    runCurrent()
    return emissions
  }

  @Test
  fun blankPatientUidIsRejected() {
    listOf("", "   ").forEach { blank ->
      assertThrows(IllegalArgumentException::class.java) { resolve(blank) }
    }
  }

  @Test
  fun ownDataWithoutPatientGrantsEverySectionEditable() = runTest {
    auth.signInWithGoogle("alice")

    val emissions = collectAccess(patientUid = null)

    assertEquals(listOf(CareAccess.Granted("alice", everything, isOwn = true)), emissions)
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun ownUidAsPatientGrantsEverySectionEditable() = runTest {
    auth.signInWithGoogle("alice")

    val emissions = collectAccess(patientUid = "alice")

    assertEquals(listOf(CareAccess.Granted("alice", everything, isOwn = true)), emissions)
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun caregiverAskingForTheirOwnDataIsDenied() = runTest {
    auth.signInWithGoogle("carol")

    // A caregiver has no patient data of their own, whichever way a screen asks for it
    assertEquals(listOf<CareAccess>(CareAccess.Denied), collectAccess(patientUid = null))
    assertEquals(listOf<CareAccess>(CareAccess.Denied), collectAccess(patientUid = "carol"))
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun patientAskingForAnotherUsersDataIsDenied() = runTest {
    profiles.seed(profile("erin", Role.PATIENT))
    // Even a member document can't give a patient access to someone else's data
    circle.setMembers("erin", listOf(member("alice", CarePermission.SYMPTOMS)))
    auth.signInWithGoogle("alice")

    val emissions = collectAccess(patientUid = "erin")

    assertEquals(listOf<CareAccess>(CareAccess.Denied), emissions)
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun profileNotOnThisDeviceIsUnconfirmedUntilItArrives() = runTest {
    auth.signInWithGoogle("frank")
    val emissions = collectAccess(patientUid = null)

    // The profile reaches the device: frank is a patient
    profiles.seed(profile("frank", Role.PATIENT))
    runCurrent()

    assertEquals(
        listOf(
            CareAccess.Unconfirmed,
            CareAccess.Granted("frank", everything, isOwn = true),
        ),
        emissions,
    )
  }

  @Test
  fun profileNotOnThisDeviceStartsNoMemberListener() = runTest {
    circle.setMembers("alice", listOf(member("frank", CarePermission.SYMPTOMS)))
    auth.signInWithGoogle("frank")

    val emissions = collectAccess(patientUid = "alice")

    assertEquals(listOf<CareAccess>(CareAccess.Unconfirmed), emissions)
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun caregiverGetsOnlyTheSharedSectionsReadOnly() = runTest {
    circle.setMembers(
        "alice",
        listOf(member("carol", CarePermission.SYMPTOMS, CarePermission.PLANNING)),
    )
    auth.signInWithGoogle("carol")

    val emissions = collectAccess(patientUid = "alice")

    assertEquals(
        listOf(
            CareAccess.Granted(
                "alice",
                setOf(CarePermission.SYMPTOMS, CarePermission.PLANNING),
                isOwn = false,
            )
        ),
        emissions,
    )
    assertEquals(listOf("alice"), circle.observedOwners)
  }

  @Test
  fun permissionChangeReEmitsTheNewSections() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.SYMPTOMS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    // The patient turns symptoms off and prescriptions on
    circle.setMembers("alice", listOf(member("carol", CarePermission.PRESCRIPTIONS)))
    runCurrent()

    assertEquals(
        listOf(
            CareAccess.Granted("alice", setOf(CarePermission.SYMPTOMS), isOwn = false),
            CareAccess.Granted("alice", setOf(CarePermission.PRESCRIPTIONS), isOwn = false),
        ),
        emissions,
    )
  }

  @Test
  fun memberRemovedFromTheCircleIsDenied() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.SYMPTOMS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    circle.setMembers("alice", emptyList())
    runCurrent()

    assertEquals(CareAccess.Denied, emissions.last())
    assertEquals(2, emissions.size)
  }

  @Test
  fun removedMemberAddedAgainIsGrantedAgain() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.SYMPTOMS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    circle.setMembers("alice", emptyList())
    runCurrent()
    // The patient invites the caregiver again, now sharing planning
    circle.setMembers("alice", listOf(member("carol", CarePermission.PLANNING)))
    runCurrent()

    assertEquals(
        listOf(
            CareAccess.Granted("alice", setOf(CarePermission.SYMPTOMS), isOwn = false),
            CareAccess.Denied,
            CareAccess.Granted("alice", setOf(CarePermission.PLANNING), isOwn = false),
        ),
        emissions,
    )
    // The same listener follows the whole story: the screen doesn't need reopening
    assertEquals(listOf("alice"), circle.observedOwners)
  }

  @Test
  fun userNeverAddedToTheCircleIsDenied() = runTest {
    auth.signInWithGoogle("bob")

    val emissions = collectAccess(patientUid = "alice")

    assertEquals(listOf<CareAccess>(CareAccess.Denied), emissions)
  }

  @Test
  fun unconfirmedMembershipIsUnconfirmed() = runTest {
    circle.unconfirmedMembers = setOf("alice" to "carol")
    auth.signInWithGoogle("carol")

    val emissions = collectAccess(patientUid = "alice")

    assertEquals(listOf<CareAccess>(CareAccess.Unconfirmed), emissions)
  }

  @Test
  fun unconfirmedThenServerConfirmsMemberIsGranted() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.SYMPTOMS)))
    circle.unconfirmedMembers = setOf("alice" to "carol")
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    // The server answers: carol is in alice's circle
    circle.unconfirmedMembers -= "alice" to "carol"
    runCurrent()

    assertEquals(
        listOf(
            CareAccess.Unconfirmed,
            CareAccess.Granted("alice", setOf(CarePermission.SYMPTOMS), isOwn = false),
        ),
        emissions,
    )
  }

  @Test
  fun unconfirmedThenServerConfirmsNotMemberIsDenied() = runTest {
    circle.unconfirmedMembers = setOf("alice" to "carol")
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    // The server answers that carol isn't in alice's circle: the access must not stay unconfirmed
    circle.unconfirmedMembers -= "alice" to "carol"
    runCurrent()

    assertEquals(listOf(CareAccess.Unconfirmed, CareAccess.Denied), emissions)
  }

  @Test
  fun unconfirmedEmitsNoPatientSections() = runTest {
    circle.setMembers("alice", listOf(member("carol", *CarePermission.entries.toTypedArray())))
    circle.unconfirmedMembers = setOf("alice" to "carol")
    auth.signInWithGoogle("carol")

    val emissions = collectAccess(patientUid = "alice")

    // Nothing of alice's data may be shown before the server confirms carol's access
    assertEquals(listOf<CareAccess>(CareAccess.Unconfirmed), emissions)
    assertFalse(emissions.any { it is CareAccess.Granted })
  }

  @Test
  fun memberWithNoSectionSharedIsGrantedNothing() = runTest {
    circle.setMembers("alice", listOf(member("carol")))
    auth.signInWithGoogle("carol")

    val emissions = collectAccess(patientUid = "alice")

    val access = emissions.single() as CareAccess.Granted
    assertEquals(emptySet<CarePermission>(), access.sections)
    CarePermission.entries.forEach { assertFalse(access.canSee(it)) }
  }

  @Test
  fun signedOutIsDeniedForOwnAndPatientData() = runTest {
    assertEquals(listOf<CareAccess>(CareAccess.Denied), collectAccess(patientUid = null))
    assertEquals(listOf<CareAccess>(CareAccess.Denied), collectAccess(patientUid = "alice"))
    assertEquals(emptyList<String>(), circle.observedOwners)
  }

  @Test
  fun signingOutStopsTheMemberListener() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.EVENTS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")
    assertEquals(1, circle.activeObservations)

    auth.signOut()
    runCurrent()

    assertEquals(CareAccess.Denied, emissions.last())
    assertEquals(0, circle.activeObservations)
  }

  @Test
  fun accountSwitchCancelsThePreviousMemberListener() = runTest {
    circle.setMembers(
        "alice",
        listOf(member("carol", CarePermission.EVENTS), member("dave", CarePermission.SYMPTOMS)),
    )
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    auth.signInWithGoogle("dave")
    runCurrent()
    // A later change to carol's permissions must no longer reach dave's screen
    circle.setMembers(
        "alice",
        listOf(member("carol", CarePermission.PLANNING), member("dave", CarePermission.SYMPTOMS)),
    )
    runCurrent()

    assertEquals(
        listOf(
            CareAccess.Granted("alice", setOf(CarePermission.EVENTS), isOwn = false),
            CareAccess.Granted("alice", setOf(CarePermission.SYMPTOMS), isOwn = false),
        ),
        emissions,
    )
    assertEquals(1, circle.activeObservations)
  }

  @Test
  fun patientSigningInOnTheSameDeviceSeesTheirOwnData() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.EVENTS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    auth.signInWithGoogle("alice")
    runCurrent()

    assertEquals(CareAccess.Granted("alice", everything, isOwn = true), emissions.last())
    assertEquals(0, circle.activeObservations)
  }

  @Test
  fun sameUserSigningInAgainDoesNotReEmit() = runTest {
    circle.setMembers("alice", listOf(member("carol", CarePermission.EVENTS)))
    auth.signInWithGoogle("carol")
    val emissions = collectAccess(patientUid = "alice")

    // A profile refresh of the same account, and a change to another member of the circle
    auth.signInWithGoogle("carol")
    circle.setMembers(
        "alice",
        listOf(member("carol", CarePermission.EVENTS), member("dave", CarePermission.SYMPTOMS)),
    )
    runCurrent()

    assertEquals(1, emissions.size)
    assertEquals(listOf("alice"), circle.observedOwners)
  }

  @Test
  fun circleReadFailureFailsTheFlow() = runTest {
    val error = IllegalStateException("PERMISSION_DENIED")
    circle.observeError = error
    auth.signInWithGoogle("carol")

    assertAccessFailsWith(error, patientUid = "alice")
  }

  @Test
  fun profileReadFailureFailsTheFlow() = runTest {
    val error = IllegalStateException("profile unavailable")
    profiles.getProfileError = error
    auth.signInWithGoogle("alice")

    assertAccessFailsWith(error, patientUid = null)
  }

  @Test
  fun authFailureFailsTheFlow() = runTest {
    val error = IllegalStateException("auth unavailable")
    auth.observeError = error

    assertAccessFailsWith(error, patientUid = null)
  }

  /** Collects [resolve] for [patientUid] and checks that it fails with [error]. */
  private fun TestScope.assertAccessFailsWith(error: Exception, patientUid: String?) {
    var failure: Throwable? = null
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      resolve(patientUid).catch { failure = it }.collect {}
    }
    runCurrent()

    // Coroutines may copy the exception to recover its stack trace, so compare its content
    assertNotNull("The flow should fail", failure)
    assertEquals(error::class, failure?.let { it::class })
    assertEquals(error.message, failure?.message)
  }
}
