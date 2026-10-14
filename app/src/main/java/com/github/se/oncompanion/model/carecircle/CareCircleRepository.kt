// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow

/**
 * Reads a user's care circle, and lets the patient change or remove its members. Members are only
 * added by redeeming an invite ([CareCircleInviteRepository.redeem]). Use
 * [CareCircleRepositoryFirestore] in the app.
 */
interface CareCircleRepository {

  /**
   * Emits the members of [ownerUid]'s care circle now and every time it changes, sorted by name.
   * Works offline from the local cache. The flow fails if the circle can't be read (e.g. the
   * security rules deny it).
   */
  fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>>

  /**
   * Emits the member [memberUid] of [ownerUid]'s care circle now and every time it changes, or null
   * when they aren't in the circle (never added, removed, or stored without a first name). Works
   * offline from the local cache. The flow fails if the member can't be read (e.g. the security
   * rules deny it).
   */
  fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?>

  /**
   * Replaces what the patient decided about [memberUid] in [ownerUid]'s circle: name, relationship
   * and permissions. A member who is no longer in the circle (e.g. removed on another device) isn't
   * brought back. Returns once saved on the device, also offline.
   *
   * @throws IllegalArgumentException if [details] aren't valid ([CareCircleMemberDetails.isValid])
   */
  suspend fun updateMember(ownerUid: String, memberUid: String, details: CareCircleMemberDetails)

  /**
   * Removes [memberUid] from [ownerUid]'s circle, so they can no longer see the patient's data once
   * the removal reaches the server. Also deletes the member's link to this circle
   * (`/users/{memberUid}/patients/{ownerUid}`), so they aren't left with a circle they can't read.
   * Returns once saved on the device, also offline.
   */
  suspend fun removeMember(ownerUid: String, memberUid: String)
}
