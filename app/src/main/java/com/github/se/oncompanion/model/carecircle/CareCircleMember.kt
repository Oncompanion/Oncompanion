// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import java.time.Instant

/** What a care circle member is to the patient, picked from a fixed list when adding them. */
enum class Relationship {
  WIFE,
  HUSBAND,
  PARTNER,
  MOTHER,
  FATHER,
  DAUGHTER,
  SON,
  SISTER,
  BROTHER,
  FRIEND,
  HOME_NURSE,
  OTHER;

  companion object {
    /** The relationship stored as [name], or [OTHER] if it is missing or unknown (e.g. removed). */
    fun fromName(name: String?): Relationship = entries.firstOrNull { it.name == name } ?: OTHER
  }
}

/**
 * A section of the patient's data that a care circle member can see. Access is always read-only: a
 * member never edits or logs anything for the patient. Declared in display order.
 */
enum class CarePermission {
  PLANNING,
  EVENTS,
  SYMPTOMS,
  PRESCRIPTIONS;

  companion object {
    /** The permissions stored as [names], ignoring unknown ones, in display order. */
    fun fromNames(names: Collection<String>): Set<CarePermission> =
        entries.filter { it.name in names }.toSet()
  }
}

/**
 * A person in the signed-in user's care circle, stored at `/users/{ownerUid}/circle/{uid}`.
 *
 * @property uid the member's Firebase Auth user ID, also the document ID
 * @property firstName as typed by the patient when adding the member
 * @property familyName optional, as typed by the patient
 * @property relationship what the member is to the patient
 * @property permissions what the member can see, read-only
 * @property email the member's Google account email, copied from their sign-in when they join;
 *   never typed or edited by the patient (see docs/architecture.md, "Care circle invitations")
 * @property addedAt when the member joined the circle, or null if unknown
 */
data class CareCircleMember(
    val uid: String,
    val firstName: String,
    val familyName: String? = null,
    val relationship: Relationship = Relationship.OTHER,
    val permissions: Set<CarePermission> = emptySet(),
    val email: String? = null,
    val addedAt: Instant? = null,
) {
  /** What the patient decided about this member: name, relationship and permissions. */
  val details: CareCircleMemberDetails
    get() = CareCircleMemberDetails(firstName, familyName, relationship, permissions)

  /** First and family name, e.g. "Sophie Dubois". */
  val fullName: String
    get() = details.fullName

  /** The member's initial, shown in their avatar. */
  val initial: String
    get() = details.initial

  /** Whether the member can see every section. */
  val hasFullAccess: Boolean
    get() = details.hasFullAccess

  companion object {
    /**
     * The order members are listed in: by full name ignoring case, then by uid so members with the
     * same name always appear in the same order.
     */
    val BY_NAME: Comparator<CareCircleMember> =
        compareBy<CareCircleMember, String>(String.CASE_INSENSITIVE_ORDER) { it.fullName }
            .thenBy { it.uid }
  }
}
