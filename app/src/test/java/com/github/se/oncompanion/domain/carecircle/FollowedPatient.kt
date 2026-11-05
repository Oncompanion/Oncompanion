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
 * For tests of caregiver mode: "caregiver" is signed in and follows "patient", who shares the
 * [shared] sections with them and lets them add to [canAdd]. The test can change that while a
 * screen shows the patient's data.
 */
class FollowedPatient(
    vararg shared: CarePermission,
    canAdd: Set<CarePermission> = emptySet(),
) {
  val auth = FakeAuthRepository {
    AuthUser(it)
  }
      .also { runBlocking { it.signInWithGoogle("caregiver") } }
  val circle = FakeCareCircleRepository()
  private val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile("caregiver", Role.CAREGIVER, firstName = "Sam"))
      }

  /** Whose care the caregiver may see. */
  val careAccess = ResolveCareCircleAccess(auth, circle, profiles)

  /** What the caregiver may read of each section. */
  val sectionAccess = ResolveSectionAccess(careAccess, auth)

  init {
    shares(*shared, canAdd = canAdd)
  }

  /**
   * The patient now shares exactly [sections] with the caregiver, and lets them add to [canAdd].
   */
  fun shares(vararg sections: CarePermission, canAdd: Set<CarePermission> = emptySet()) =
      circle.setMembers(
          "patient",
          listOf(
              CareCircleMember(
                  uid = "caregiver",
                  firstName = "Sam",
                  permissions = sections.toSet(),
                  canAdd = canAdd,
              )
          ),
      )

  /** The patient removes the caregiver from their circle. */
  fun removesTheCaregiver() = circle.setMembers("patient", emptyList())

  /** The caregiver's membership isn't confirmed by the server yet (offline, nothing cached). */
  fun isUnconfirmed() {
    circle.unconfirmedMembers = setOf("patient" to "caregiver")
  }
}

/**
 * A [ResolveSectionAccess] where "caregiver" follows "patient", who shares [shared] and lets them
 * add to [canAdd].
 */
fun followedPatientAccess(
    vararg shared: CarePermission,
    canAdd: Set<CarePermission> = emptySet(),
): ResolveSectionAccess = FollowedPatient(*shared, canAdd = canAdd).sectionAccess
