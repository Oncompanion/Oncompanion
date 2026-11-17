// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import kotlinx.coroutines.flow.Flow

/**
 * Tells a patient what their care circle did for them (US-50), and remembers which notifications
 * they have seen.
 *
 * Reads work offline from the local cache, and writes return as soon as they are saved on the
 * device (also offline); they reach the server when there is a connection.
 */
interface NotificationRepository {

  /**
   * Notifies [recipientUid] of [notification], right after its item was saved: the server receives
   * the item first, so the rules can check it. Its `createdAt` is ignored and set by the server,
   * and Firestore deletes it after [CircleNotification.KEPT_FOR].
   *
   * Sent once per item: sending it again is refused by the server and ignored, so a retry never
   * makes a seen notification unseen. A refusal is never reported to the user, whose item is saved.
   *
   * @throws IllegalArgumentException if the notification is not [CircleNotification.isValid]
   */
  suspend fun notify(recipientUid: String, notification: CircleNotification)

  /**
   * Emits the notifications of [uid] from the last [CircleNotification.KEPT_FOR], newest first, now
   * and every time they change. Older ones are left out even before Firestore deletes them.
   */
  fun observeRecent(uid: String): Flow<List<CircleNotification>>

  /**
   * Marks the notifications [ids] of [uid] as seen. Each one is saved on its own, so one that no
   * longer exists (deleted after 7 days) doesn't stop the others.
   */
  suspend fun markSeen(uid: String, ids: Collection<String>)
}
