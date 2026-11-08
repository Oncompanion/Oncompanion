// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.domain.carecircle.ResolveCareCircleAccess
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * Profiles that make everyone a patient, without a name: for tests of the user's own planning,
 * where the role only has to let them see it.
 */
object PatientProfiles : UserProfileRepository by FakeUserProfileRepository() {
  override fun observeProfile(uid: String): Flow<UserProfile?> =
      flowOf(UserProfile(uid, Role.PATIENT, firstName = ""))
}

/** An auth fake already signed in as "patient". */
fun signedInPatient(): FakeAuthRepository = FakeAuthRepository {
  AuthUser(it)
}
    .also { runBlocking { it.signInWithGoogle("patient") } }

/**
 * The [ObserveCarePlanning] of tests about the user's own planning: whoever [auth] signs in is a
 * patient and sees their own [planning].
 */
fun ownPlanning(
    planning: PlanningRepository,
    auth: AuthRepository = signedInPatient(),
): ObserveCarePlanning {
  val circle = FakeCareCircleRepository()
  return ObserveCarePlanning(
      ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, PatientProfiles), auth),
      planning,
      circle,
  )
}

/**
 * The [ObserveCarePlanning] of a caregiver signed in as "caregiver" who follows "patient", sees the
 * [shared] sections of their [planning], and may add to the [canAdd] ones.
 */
fun followedPlanning(
    planning: PlanningRepository,
    shared: Set<CarePermission>,
    canAdd: Set<CarePermission> = emptySet(),
): ObserveCarePlanning {
  val auth = FakeAuthRepository {
    AuthUser(it)
  }
      .also { runBlocking { it.signInWithGoogle("caregiver") } }
  val circle =
      FakeCareCircleRepository().apply {
        setMembers(
            "patient",
            listOf(
                CareCircleMember(
                    uid = "caregiver",
                    firstName = "Sam",
                    permissions = shared,
                    canAdd = canAdd,
                )
            ),
        )
      }
  val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile("caregiver", Role.CAREGIVER, firstName = "Sam"))
      }
  return ObserveCarePlanning(
      ResolveSectionAccess(ResolveCareCircleAccess(auth, circle, profiles), auth),
      planning,
      circle,
  )
}
