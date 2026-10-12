// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow

/**
 * Pending care circle members (invitations), created by the patient. Use
 * [CareCircleInviteRepositoryFirestore] in the app.
 */
interface CareCircleInviteRepository {

  /**
   * Creates an invitation to [ownerUid]'s circle for [details], to be sent to [email], and returns
   * it with its new invite code. Returns once saved on the device (also offline); the invite code
   * works once it reaches the server ([CareCircleInvite.isSynced]).
   *
   * @param ownerFirstName the patient's first name, shown to the person before they join
   * @throws IllegalArgumentException if [details] is not valid or [email] is not a valid email
   */
  suspend fun createInvite(
      ownerUid: String,
      ownerFirstName: String,
      details: CareCircleMemberDetails,
      email: String?,
  ): CareCircleInvite

  /**
   * Emits [ownerUid]'s unused invitations, sorted by name, now and on every change (including when
   * one reaches the server). Works offline from the local cache. The flow fails if the invitations
   * can't be read.
   */
  fun observePendingInvites(ownerUid: String): Flow<List<CareCircleInvite>>

  /**
   * Emits one invitation now and on every change, or null once it is used, cancelled or missing.
   * Works offline from the local cache. The flow fails if it can't be read.
   */
  fun observeInvite(inviteCode: String): Flow<CareCircleInvite?>

  /**
   * Replaces the details and email of an unused invitation. Returns once saved on the device.
   *
   * @throws IllegalArgumentException if [details] is not valid or [email] is not a valid email
   */
  suspend fun updateInvite(inviteCode: String, details: CareCircleMemberDetails, email: String?)

  /**
   * Deletes the invitation; its invite code stops working. Returns once saved on the device. If the
   * person redeems it before the deletion reaches the server, they still join and the patient can
   * remove them.
   */
  suspend fun cancelInvite(inviteCode: String)

  /**
   * Replaces an expired invitation with a new invite code for the same person, in one write.
   * Returns the new invitation once saved on the device.
   */
  suspend fun renewInvite(expired: CareCircleInvite): CareCircleInvite
}
