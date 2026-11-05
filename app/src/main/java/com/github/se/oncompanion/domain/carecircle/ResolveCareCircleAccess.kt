// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMembership
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Decides whose data the signed-in user is looking at and which sections they may see: a patient's
 * own data in full, or what a patient shares with a caregiver in their care circle. Every screen
 * that shows patient data asks it, so caregiver mode is not a separate code path.
 *
 * It only decides what the UI shows and offers: `firestore.rules` are the real gate on what can be
 * read and written.
 */
class ResolveCareCircleAccess(
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val circle: CareCircleRepository = CareCircleRepositoryProvider.repository,
    private val profiles: UserProfileRepository = UserProfileRepositoryProvider.repository,
) {

  /**
   * Emits the access to [patientUid]'s data now and every time it changes (sign-in or out, the
   * patient changing this caregiver's permissions or removing them), so open screens follow it.
   * [patientUid] is null for the signed-in user's own data, or a real uid, never blank; their own
   * uid also means their own data. The profile role decides what may be asked: a patient only sees
   * their own data, and a caregiver only the data of a patient whose circle they are in, so both
   * get [CareAccess.Denied] otherwise. Reads the local cache: a profile or member document this
   * device has a record of answers right away, and one it has no record of is
   * [CareAccess.Unconfirmed] until the server confirms (see
   * [CareCircleRepository.observeMembership]). The flow fails if the profile or the care circle
   * can't be read, like the repositories'.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  operator fun invoke(patientUid: String?): Flow<CareAccess> {
    require(patientUid == null || patientUid.isNotBlank()) { "patientUid must not be blank" }
    return auth
        .observeCurrentUser()
        .map { it?.uid }
        .distinctUntilChanged()
        // A new account cancels the previous account's listeners
        .flatMapLatest { me ->
          if (me == null) {
            flowOf(CareAccess.Denied)
          } else {
            profiles
                .observeProfile(me)
                .map { it?.role }
                .distinctUntilChanged()
                .flatMapLatest { role -> accessFor(me, role, patientUid) }
          }
        }
        .distinctUntilChanged()
  }

  /** The access [me], whose profile has [role] (null when not known yet), has to [patientUid]. */
  private fun accessFor(me: String, role: Role?, patientUid: String?): Flow<CareAccess> {
    // Someone else's uid, or null for the user's own data
    val otherUid = patientUid?.takeIf { it != me }
    return when {
      // No profile on this device yet (or onboarding not finished): show nothing until it's known
      role == null -> flowOf(CareAccess.Unconfirmed)
      role == Role.PATIENT ->
          flowOf(
              if (otherUid == null)
                  CareAccess.Granted(me, CarePermission.entries.toSet(), isOwn = true)
              else CareAccess.Denied
          )
      // A caregiver has no patient data of their own
      otherUid == null -> flowOf(CareAccess.Denied)
      else -> membershipAccess(me, otherUid)
    }
  }

  /** What [me]'s member document in [patientUid]'s circle grants, followed live. */
  private fun membershipAccess(me: String, patientUid: String): Flow<CareAccess> =
      circle.observeMembership(patientUid, me).map { membership ->
        when (membership) {
          is CareCircleMembership.Member ->
              CareAccess.Granted(
                  patientUid,
                  membership.member.permissions,
                  isOwn = false,
                  addable = membership.member.canAdd intersect membership.member.permissions,
              )
          CareCircleMembership.NotMember -> CareAccess.Denied
          CareCircleMembership.Unconfirmed -> CareAccess.Unconfirmed
        }
      }
}
