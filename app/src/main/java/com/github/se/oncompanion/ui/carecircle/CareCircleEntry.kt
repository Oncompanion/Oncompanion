// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails

/**
 * One row of the care circle list: someone who joined, or an invitation not used yet. Both show the
 * same [details], so the list draws them with the same layout.
 */
sealed interface CareCircleEntry {
  /** Unique among every row of the list, members and invitations together. */
  val key: String

  /** The name, relationship and access the patient chose for this person. */
  val details: CareCircleMemberDetails

  /** Someone who used their invite code: they can see what [member] allows. */
  data class Joined(val member: CareCircleMember) : CareCircleEntry {
    override val key: String
      get() = "member_${member.uid}"

    override val details: CareCircleMemberDetails
      get() = member.details
  }

  /**
   * An invitation waiting for its invite code to be used.
   *
   * @property expired whether the invite code can no longer be used, as of when the list was read
   */
  data class Pending(val invite: CareCircleInvite, val expired: Boolean) : CareCircleEntry {
    override val key: String
      get() = "invite_${invite.inviteCode}"

    override val details: CareCircleMemberDetails
      get() = invite.details
  }
}
