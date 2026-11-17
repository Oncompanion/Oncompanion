// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import android.util.Log
import com.github.se.oncompanion.model.session.SessionAccess
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [NotificationRepository] backed by Cloud Firestore: a notification is one document at
 * `/users/{uid}/notifications/{kind}_{itemId}`, written by the caregiver's phone.
 *
 * The user reads only their own, by the path, and may only mark them seen; a caregiver may only
 * create one about their own item still waiting for the patient (firestore.rules). Firestore
 * deletes each one through a TTL policy on `expiresAt`.
 *
 * @param clock the current time: the 7-day window of the reads and the expiry of a new notification
 */
class NotificationRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
    private val clock: () -> Instant = Instant::now,
) : NotificationRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(
      db: FirebaseFirestore,
      clock: () -> Instant = Instant::now,
  ) : this({ db }, clock)

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeRecent(uid: String): Flow<List<CircleNotification>> =
      SessionAccess.snapshots {
        // Firestore's TTL can take a day to delete them: leave out the older ones here too
        val since = Timestamp(clock().minus(CircleNotification.KEPT_FOR))
        val registration =
            notifications(uid)
                .whereGreaterThan(FIELD_CREATED_AT, since)
                .orderBy(FIELD_CREATED_AT, Query.Direction.DESCENDING)
                .addSnapshotListener { snapshot, error ->
                  if (error != null) {
                    close(error)
                  } else if (snapshot != null) {
                    trySend(snapshot.documents.mapNotNull(::toNotification))
                  }
                }
        awaitClose { registration.remove() }
      }

  override suspend fun notify(recipientUid: String, notification: CircleNotification): Unit =
      SessionAccess.operation {
        require(notification.isValid()) { "Invalid notification" }
        notifications(recipientUid)
            .document(notification.id)
            .set(
                mapOf(
                    FIELD_KIND to notification.kind.name,
                    FIELD_ITEM_ID to notification.itemId,
                    FIELD_ACTOR_UID to notification.actorUid,
                    FIELD_ACTOR_FIRST_NAME to notification.actorFirstName,
                    FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                    // From the phone's clock: synced late, it expires sooner, never later (rules)
                    FIELD_EXPIRES_AT to Timestamp(clock().plus(CircleNotification.KEPT_FOR)),
                    FIELD_SEEN to false,
                )
            )
            .addOnFailureListener { e ->
              // Expected when it was already sent. Not the message: it holds the document path
              Log.w(
                  TAG,
                  "The server refused notification ${notification.id}: ${e.javaClass.simpleName}",
              )
            }
      }

  override suspend fun markSeen(uid: String, ids: Collection<String>): Unit =
      SessionAccess.operation {
        // One write each, not a batch: one that no longer exists would make a batch fail whole
        for (id in ids) {
          notifications(uid).document(id).update(FIELD_SEEN, true).addOnFailureListener { e ->
            // Not the message: it holds the document path. Nothing to tell the user, who only
            // sees the notification as unseen again
            Log.w(
                TAG,
                "The server refused marking notification $id seen: ${e.javaClass.simpleName}",
            )
          }
        }
      }

  private fun notifications(uid: String) =
      db.collection(USERS).document(uid).collection(NOTIFICATIONS)

  /**
   * The notification in [doc], or `null` if it can't be read (e.g. a kind this app doesn't know).
   */
  private fun toNotification(doc: DocumentSnapshot): CircleNotification? {
    val kind =
        NotificationKind.entries.firstOrNull { it.name == doc.getString(FIELD_KIND) } ?: return null
    return CircleNotification(
        kind = kind,
        itemId = doc.getString(FIELD_ITEM_ID) ?: return null,
        actorUid = doc.getString(FIELD_ACTOR_UID) ?: return null,
        actorFirstName = doc.getString(FIELD_ACTOR_FIRST_NAME) ?: return null,
        createdAt = doc.getTimestamp(FIELD_CREATED_AT)?.toInstant() ?: return null,
        seen = doc.getBoolean(FIELD_SEEN) ?: false,
    )
  }

  private companion object {
    const val TAG = "NotificationRepository"
    const val USERS = "users"
    const val NOTIFICATIONS = "notifications"
    const val FIELD_KIND = "kind"
    const val FIELD_ITEM_ID = "itemId"
    const val FIELD_ACTOR_UID = "actorUid"
    const val FIELD_ACTOR_FIRST_NAME = "actorFirstName"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_EXPIRES_AT = "expiresAt"
    const val FIELD_SEEN = "seen"
  }
}
