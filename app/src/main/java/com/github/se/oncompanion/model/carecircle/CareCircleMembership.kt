// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

/**
 * Whether a user is in a patient's care circle, as far as this device can tell. Unlike a plain
 * "member or null", it tells a missing member document confirmed by the server from one the server
 * hasn't confirmed yet, so a caregiver is never told they were removed before that is known. Read
 * with [CareCircleRepository.observeMembership].
 */
sealed interface CareCircleMembership {

  /** They are in the circle: [member] is their member document. */
  data class Member(val member: CareCircleMember) : CareCircleMembership

  /**
   * They aren't in the circle: never added, removed, or their member document is malformed (stored
   * without a first name).
   */
  data object NotMember : CareCircleMembership

  /**
   * Not confirmed yet: this device has no record of the member document and the server hasn't
   * answered. Becomes [Member] or [NotMember] once the server confirms.
   */
  data object Unconfirmed : CareCircleMembership
}
