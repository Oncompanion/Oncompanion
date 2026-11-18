// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import com.github.se.oncompanion.model.carecircle.CarePermission
import java.time.Duration
import java.time.Instant

/** What someone in the care circle did that a notification tells the user about (US-50). */
enum class NotificationKind(
    /** The section of the patient's data the item belongs to, for the counts on its tab. */
    val section: CarePermission
) {
  /** A caregiver added a prescription that waits for the patient's review (US-43). */
  PRESCRIPTION_ADDED(CarePermission.PRESCRIPTIONS),
}

/**
 * Something a member of the user's care circle did for them, shown in the "Your circle" sheet
 * (US-50). The caregiver's phone creates it right after saving the item; the recipient reads it and
 * marks it [seen].
 *
 * It never holds the item's content (no medication names): the screen reads the item [itemId] live,
 * so it always shows its current state and no health detail is copied.
 *
 * @property kind what happened
 * @property itemId the ID of the item it happened to, e.g. the prescription
 * @property actorUid who did it
 * @property actorFirstName their first name when they did it, kept if they later leave the circle
 * @property createdAt when the server received it
 * @property seen whether the user has seen it, for the dot on the sailboat and the tab counts
 */
data class CircleNotification(
    val kind: NotificationKind,
    val itemId: String,
    val actorUid: String,
    val actorFirstName: String,
    val createdAt: Instant,
    val seen: Boolean = false,
) {
  /** Whether firestore.rules accept it: an item, an actor, and a first name like a profile's. */
  fun isValid(): Boolean =
      itemId.isNotBlank() &&
          actorUid.isNotBlank() &&
          actorFirstName.isNotBlank() &&
          actorFirstName.length <= MAX_ACTOR_NAME_LENGTH

  /** Its document ID: one addition or decision has exactly one, so it is never stored twice. */
  val id: String
    get() = idOf(kind, itemId)

  /** The section its item belongs to: it follows from [kind], so it is never stored. */
  val section: CarePermission
    get() = kind.section

  companion object {
    /** How long a notification is shown, then deleted by Firestore. Keep in sync with the TTL. */
    val KEPT_FOR: Duration = Duration.ofDays(7)

    /** The longest first name, as for every first name in the app (UserProfile.MAX_NAME_LENGTH). */
    const val MAX_ACTOR_NAME_LENGTH = 50

    /** The document ID of the notification of [kind] about item [itemId]. */
    fun idOf(kind: NotificationKind, itemId: String) = "${kind.name}_$itemId"
  }
}

/** How many notifications are unseen in each section, for the counts on the tabs. */
fun List<CircleNotification>.unseenCountBySection(): Map<CarePermission, Int> = filterNot {
  it.seen
}
    .groupingBy { it.section }
    .eachCount()
