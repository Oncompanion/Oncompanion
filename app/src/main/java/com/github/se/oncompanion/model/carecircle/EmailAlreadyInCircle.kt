// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import java.time.Instant

/** Someone in the patient's care circle who already has the email being invited (#257). */
sealed interface EmailAlreadyInCircle {
  /** Their first name, as the patient typed it. */
  val firstName: String

  /** A member whose account has this email. */
  data class Member(override val firstName: String) : EmailAlreadyInCircle

  /** An unused invitation with this email, which the patient can open. */
  sealed interface Invite : EmailAlreadyInCircle {
    /** The invitation's invite code, which opens it on the invite screen. */
    val inviteCode: String
  }

  /** An invitation that still works: the person only has to use its code. */
  data class PendingInvite(override val firstName: String, override val inviteCode: String) : Invite

  /** An invitation that has expired: the patient can renew it from the invite screen. */
  data class ExpiredInvite(override val firstName: String, override val inviteCode: String) : Invite
}

/**
 * Finds who in the circle already has [email], so the patient doesn't invite the same person twice.
 * Spaces around an email and its case don't count. Nobody matches a blank [email], and people
 * without an email are never matched: a shared first name doesn't make two people the same.
 *
 * A [member][EmailAlreadyInCircle.Member] comes first, then a
 * [working invitation][EmailAlreadyInCircle.PendingInvite], then an
 * [expired one][EmailAlreadyInCircle.ExpiredInvite]. Among several invitations of the same kind
 * (the same person invited twice before #257), the newest one: an invitation not on the server yet
 * has no creation time and is the newest.
 *
 * @param invites the patient's unused invitations
 * @param now tells whether an invitation has expired
 */
fun emailAlreadyInCircle(
    email: String,
    members: List<CareCircleMember>,
    invites: List<CareCircleInvite>,
    now: Instant,
): EmailAlreadyInCircle? {
  members
      .firstOrNull { isSameEmail(it.email, email) }
      ?.let {
        return EmailAlreadyInCircle.Member(it.firstName)
      }
  val (expired, working) =
      invites.filter { isSameEmail(it.email, email) }.partition { it.isExpired(now) }
  working.newest()?.let {
    return EmailAlreadyInCircle.PendingInvite(it.details.firstName, it.inviteCode)
  }
  return expired.newest()?.let {
    EmailAlreadyInCircle.ExpiredInvite(it.details.firstName, it.inviteCode)
  }
}

/**
 * Whether [first] and [second] are the same email, whatever their case and the spaces around them.
 * A missing or blank email is nobody's, so it never matches.
 */
fun isSameEmail(first: String?, second: String?): Boolean =
    comparableEmail(first).let { it.isNotEmpty() && it == comparableEmail(second) }

/** The invitation created last; one without a creation time hasn't reached the server yet. */
private fun List<CareCircleInvite>.newest(): CareCircleInvite? =
    maxWithOrNull(compareBy(nullsLast()) { it.createdAt })

private fun comparableEmail(email: String?): String = email.orEmpty().trim().lowercase()
