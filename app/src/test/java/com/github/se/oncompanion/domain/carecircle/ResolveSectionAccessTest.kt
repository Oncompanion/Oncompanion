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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks whose data of a section [ResolveSectionAccess] lets a screen read: the user's own,
 * editable, or a followed patient's, read-only, when they share the section. alice is a patient,
 * bob a caregiver in her circle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResolveSectionAccessTest {

  private val auth = FakeAuthRepository { AuthUser(uid = it) }
  private val circle = FakeCareCircleRepository()
  private val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile("alice", Role.PATIENT, firstName = "Alice"))
        seed(UserProfile("bob", Role.CAREGIVER, firstName = "Bob"))
      }
  private val resolve = ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, profiles), auth)

  private fun bobSees(vararg sections: CarePermission) =
      circle.setMembers(
          "alice",
          listOf(CareCircleMember(uid = "bob", firstName = "Bob", permissions = sections.toSet())),
      )

  /** Collects the access to [section] of [patientUid] and returns what it emitted so far. */
  private fun TestScope.collect(
      section: CarePermission,
      patientUid: String?,
  ): MutableList<SectionAccess> {
    val emissions = mutableListOf<SectionAccess>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      resolve(section, patientUid).collect { emissions += it }
    }
    runCurrent()
    return emissions
  }

  @Test
  fun ownData_isReadableAndEditable_evenWithoutAProfile() = runTest {
    auth.signInWithGoogle("nobody")

    assertEquals(
        SectionAccess.Readable("nobody", canEdit = true),
        collect(CarePermission.SYMPTOMS, null).last(),
    )
  }

  @Test
  fun ownData_followsSignInAndSignOut() = runTest {
    val emissions = collect(CarePermission.SYMPTOMS, null)
    auth.signInWithGoogle("alice")
    runCurrent()
    auth.signOut()
    runCurrent()

    assertEquals(
        listOf(
            SectionAccess.NoAccess,
            SectionAccess.Readable("alice", canEdit = true),
            SectionAccess.NoAccess,
        ),
        emissions,
    )
  }

  @Test
  fun sharedSection_isReadable_butNotEditable() = runTest {
    bobSees(CarePermission.SYMPTOMS)
    auth.signInWithGoogle("bob")

    assertEquals(
        SectionAccess.Readable("alice", canEdit = false),
        collect(CarePermission.SYMPTOMS, "alice").last(),
    )
  }

  @Test
  fun otherSections_areNotShared_andFollowThePatientLive() = runTest {
    bobSees(CarePermission.SYMPTOMS)
    auth.signInWithGoogle("bob")
    val prescriptions = collect(CarePermission.PRESCRIPTIONS, "alice")
    assertEquals(SectionAccess.NotShared, prescriptions.last())

    bobSees(CarePermission.SYMPTOMS, CarePermission.PRESCRIPTIONS)
    runCurrent()

    assertEquals(SectionAccess.Readable("alice", canEdit = false), prescriptions.last())
  }

  @Test
  fun notInTheCircle_hasNoAccess_andUnconfirmed_isLoading() = runTest {
    auth.signInWithGoogle("bob")
    assertEquals(SectionAccess.NoAccess, collect(CarePermission.SYMPTOMS, "alice").last())

    bobSees(CarePermission.SYMPTOMS)
    circle.unconfirmedMembers = setOf("alice" to "bob")
    assertEquals(SectionAccess.Loading, collect(CarePermission.SYMPTOMS, "alice").last())
  }
}
