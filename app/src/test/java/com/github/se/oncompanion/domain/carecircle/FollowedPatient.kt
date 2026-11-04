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
import kotlinx.coroutines.runBlocking

/**
 * A [ResolveSectionAccess] for tests of caregiver mode: "caregiver" is signed in and follows
 * "patient", who shares the [shared] sections with them.
 */
fun followedPatientAccess(vararg shared: CarePermission): ResolveSectionAccess {
  val auth = FakeAuthRepository {
    AuthUser(it)
  }
      .also { runBlocking { it.signInWithGoogle("caregiver") } }
  val circle =
      FakeCareCircleRepository().apply {
        setMembers(
            "patient",
            listOf(
                CareCircleMember(uid = "caregiver", firstName = "Sam", permissions = shared.toSet())
            ),
        )
      }
  val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile("caregiver", Role.CAREGIVER, firstName = "Sam"))
      }
  return ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, profiles), auth)
}
