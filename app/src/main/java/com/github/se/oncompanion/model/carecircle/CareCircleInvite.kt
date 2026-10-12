// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Random

/**
 * A pending member of [ownerUid]'s care circle, stored at `/invites/{inviteCode}`: what the patient
 * decided about the person, waiting for them to enter [inviteCode]. It becomes a [CareCircleMember]
 * only once redeemed, so a pending member never needs a uid.
 *
 * @property inviteCode the document ID, normalized (upper case, no dash)
 * @property ownerUid the patient whose circle the person joins
 * @property ownerFirstName the patient's first name, copied so the caregiver can see whose circle
 *   they join (they can't read the patient's profile)
 * @property details the name, relationship and access the patient chose
 * @property email where the patient sends the invitation, if they gave one
 * @property createdAt when the server received the invite, or null until it has
 * @property redeemerUid the caregiver who used the invite code, or null while unused
 * @property isSynced false while the invite only exists on this phone: the invite code works on
 *   another phone only once the server has it. Never stored.
 */
data class CareCircleInvite(
    val inviteCode: String,
    val ownerUid: String,
    val ownerFirstName: String,
    val details: CareCircleMemberDetails,
    val email: String? = null,
    val createdAt: Instant? = null,
    val redeemerUid: String? = null,
    val isSynced: Boolean = true,
) {
  /** True once [VALIDITY] has passed since the server received the invite. */
  fun isExpired(now: Instant): Boolean = createdAt != null && now >= createdAt + VALIDITY

  /** The invite code split in two, e.g. "ABCDE-FGHJK", easier to read aloud or type. */
  val displayInviteCode: String
    get() = inviteCode.chunked(INVITE_CODE_LENGTH / 2).joinToString("-")

  companion object {
    /**
     * How long an invite code can be used after the server received it. Keep in sync with
     * firestore.rules.
     */
    val VALIDITY: Duration = Duration.ofDays(7)
    const val INVITE_CODE_LENGTH = 10
    /** No 0/O nor 1/I/L: invite codes are read aloud and typed by hand. */
    const val INVITE_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    /** Longest email address (RFC 5321). Keep in sync with firestore.rules. */
    const val MAX_EMAIL_LENGTH = 254
    /**
     * The order invitations are listed in: by full name ignoring case, then by invite code, like
     * [CareCircleMember.BY_NAME].
     */
    val BY_NAME: Comparator<CareCircleInvite> =
        compareBy<CareCircleInvite, String>(String.CASE_INSENSITIVE_ORDER) { it.details.fullName }
            .thenBy { it.inviteCode }
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /**
     * A new random invite code. It is generated on the phone, so inviting works offline, and is
     * hard to guess (31^10 possibilities).
     */
    fun generateInviteCode(random: Random = SecureRandom()): String =
        String(
            CharArray(INVITE_CODE_LENGTH) {
              INVITE_CODE_ALPHABET[random.nextInt(INVITE_CODE_ALPHABET.length)]
            }
        )

    /** What the user typed turned into the stored form: upper case, spaces and dashes removed. */
    fun normalizeInviteCode(input: String): String =
        input.uppercase().filter { it.isLetterOrDigit() }

    /** Whether [inviteCode] has the shape of a generated invite code (already normalized). */
    fun isWellFormedInviteCode(inviteCode: String): Boolean =
        inviteCode.length == INVITE_CODE_LENGTH && inviteCode.all { it in INVITE_CODE_ALPHABET }

    /**
     * A loose email check, enough to catch typos; the email app does the real one. Avoids Android's
     * `Patterns` so the model stays testable on the JVM.
     */
    fun isValidEmail(email: String): Boolean =
        email.length <= MAX_EMAIL_LENGTH && EMAIL.matches(email)
  }
}
