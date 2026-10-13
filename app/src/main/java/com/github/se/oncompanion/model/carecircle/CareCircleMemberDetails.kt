// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

/**
 * What the patient decides about someone in their circle: the name they know them by, how they are
 * related, and what they can see. The same for an invitation and a member, and the only part the
 * patient can edit.
 *
 * @property firstName as typed by the patient
 * @property familyName optional, as typed by the patient
 * @property relationship what the person is to the patient
 * @property permissions what the person can see, read-only
 */
data class CareCircleMemberDetails(
    val firstName: String,
    val familyName: String? = null,
    val relationship: Relationship = Relationship.OTHER,
    val permissions: Set<CarePermission> = emptySet(),
) {
  /** First and family name, e.g. "Sophie Dubois". */
  val fullName: String
    get() = listOfNotNull(firstName, familyName?.takeIf { it.isNotBlank() }).joinToString(" ")

  /** The person's initial, shown in their avatar. */
  val initial: String
    get() = firstName.trim().take(1).uppercase()

  /** Whether the person can see every section. */
  val hasFullAccess: Boolean
    get() = permissions.containsAll(CarePermission.entries)

  /**
   * Whether these details can be saved: a non-blank first name and names of at most
   * [MAX_NAME_LENGTH] characters. Same constraints as `isValidCareCircleMemberDetails` in
   * firestore.rules, so the app refuses what the server would refuse.
   */
  fun isValid(): Boolean =
      firstName.isNotBlank() &&
          firstName.length <= MAX_NAME_LENGTH &&
          (familyName == null || familyName.length <= MAX_NAME_LENGTH)

  companion object {
    /** Longest first or family name. Keep in sync with firestore.rules. */
    const val MAX_NAME_LENGTH = 50
  }
}
