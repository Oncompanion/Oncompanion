// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow

/**
 * Pending care circle members (invitations), created by the patient. Use
 * [CareCircleInviteRepositoryFirestore] in the app.
 *
 * Writes return right away, without waiting for the server, so they never block the UI: the write
 * is queued on this device and shown at once by reads and listeners, also offline. It reaches the
 * server later; if the server rejects it, Firestore undoes it and the failure is only logged, the
 * caller isn't told.
 */
interface CareCircleInviteRepository {

  /**
   * Creates an invitation to [ownerUid]'s circle for [details], to be sent to [email], and returns
   * it with its new invite code. Returns right away (see the class documentation); the invite code
   * only works on another phone once the server has it, which [CareCircleInvite.isSynced] tells.
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
   * Emits [ownerUid]'s invitation [inviteCode] now and on every change, or null once it is used,
   * cancelled or missing. Works offline from the local cache. The flow fails if it can't be read.
   */
  fun observeInvite(ownerUid: String, inviteCode: String): Flow<CareCircleInvite?>

  /**
   * Replaces the details and email of an unused invitation. Returns right away (see the class
   * documentation).
   *
   * @throws IllegalArgumentException if [details] is not valid or [email] is not a valid email
   */
  suspend fun updateInvite(inviteCode: String, details: CareCircleMemberDetails, email: String?)

  /**
   * Deletes the invitation; its invite code stops working. Returns right away (see the class
   * documentation). If the person redeems it before the deletion reaches the server, they still
   * join and the patient can remove them.
   */
  suspend fun cancelInvite(inviteCode: String)

  /**
   * Replaces an expired invitation with a new invite code for the same person, in one write.
   * Returns the new invitation right away (see the class documentation).
   */
  suspend fun renewInvite(expired: CareCircleInvite): CareCircleInvite

  /**
   * Joins the circle of the invitation [inviteCode] (as typed, e.g. "abcde-fghjk") as
   * [caregiverUid], sharing [caregiverEmail] (their account email) with the patient, and deletes
   * the invitation so its invite code can't be used again. Unlike the other writes, it needs a
   * connection and is never saved on the device first: only the server knows whether the invite
   * code is still valid (see architecture.md). Only a caregiver account can join.
   */
  suspend fun redeem(
      inviteCode: String,
      caregiverUid: String,
      caregiverEmail: String?,
  ): RedeemResult
}

/** What happened when a caregiver entered an invite code. */
sealed interface RedeemResult {
  /** The caregiver is now a member of [patientUid]'s circle. */
  data class Joined(val patientUid: String, val patientFirstName: String) : RedeemResult

  /** Already in this patient's circle (e.g. a second invitation from the same patient). */
  data class AlreadyMember(val patientFirstName: String) : RedeemResult

  /**
   * Unknown, malformed, already used, expired, or the caregiver's own invite code. These aren't
   * told apart, so nobody can learn which invite codes exist.
   */
  data object InvalidOrUsed : RedeemResult

  /**
   * The server couldn't be reached. Nothing was saved, not even on the device: try again online.
   */
  data object Offline : RedeemResult
}
