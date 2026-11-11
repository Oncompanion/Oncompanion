// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import android.util.Log
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectedWritesProvider
import com.github.se.oncompanion.model.session.SessionAccess
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [EventRegistrationRepository] backed by Cloud Firestore, one document per confirmed event at
 * `/users/{uid}/eventRegistrations/{eventId}`. Keying it by the event makes confirming twice write
 * the same document, so there are never duplicates.
 *
 * Writes return once saved on the phone and sync when the connection returns. If the server rejects
 * one (e.g. the event started before the phone synced), it is rolled back on the device, which
 * [observeRegistration] then emits, and reported through [RejectedWrites], which tells the user.
 */
class EventRegistrationRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
    private val rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
) : EventRegistrationRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(
      db: FirebaseFirestore,
      rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
  ) : this({ db }, rejectedWrites)

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeRegistration(uid: String, eventId: String): Flow<EventRegistration?> {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    return SessionAccess.snapshots {
      val document = document(uid, eventId)
      val registration = document.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
        } else {
          trySend(
              snapshot
                  ?.takeIf { it.exists() }
                  ?.let {
                    EventRegistration(
                        it.getString(FIELD_ADDED_BY),
                        it.getString(FIELD_ADDED_BY_NAME),
                    )
                  }
          )
        }
      }
      awaitClose { registration.remove() }
    }
  }

  override fun observeRegisteredEventIds(uid: String): Flow<Set<String>> {
    requirePathSegment(uid)
    return SessionAccess.snapshots {
      // Each document is named after the event it confirms, so its id is all that is needed
      val registrations =
          registrations(uid).addSnapshotListener { snapshot, error ->
            // Firestore gives either a snapshot or an error
            if (snapshot != null) trySend(snapshot.documents.map { it.id }.toSet())
            else close(error)
          }
      awaitClose { registrations.remove() }
    }
  }

  override fun confirm(uid: String, eventId: String, by: EventRegistration): Unit =
      SessionAccess.immediate {
        // The server sets the time, so the rules can check it against the event's start. The
        // patient's own confirmation says nothing about who added it, as the rules require
        val signature =
            if (by.addedBy == null) emptyMap()
            else mapOf(FIELD_ADDED_BY to by.addedBy, FIELD_ADDED_BY_NAME to by.addedByName)
        document(uid, eventId)
            .set(mapOf(FIELD_CONFIRMED_AT to FieldValue.serverTimestamp()) + signature)
            .addOnFailureListener { reportRejected("Confirmation for event $eventId", it) }
      }

  override fun remove(uid: String, eventId: String): Unit = SessionAccess.immediate {
    document(uid, eventId).delete().addOnFailureListener {
      reportRejected("Removing the confirmation for event $eventId", it)
    }
  }

  /** Logs and reports to [rejectedWrites] the server rejecting [what], already rolled back. */
  private fun reportRejected(what: String, e: Exception) {
    Log.e(TAG, "$what was rejected", e)
    rejectedWrites.report()
  }

  private fun document(uid: String, eventId: String): DocumentReference {
    // Checked before `db` is first used, so a bad id fails the same way with or without Firebase
    requirePathSegment(uid)
    requirePathSegment(eventId)
    return registrations(uid).document(eventId)
  }

  private fun registrations(uid: String): CollectionReference =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  /** Throws unless [value] can be a document id: Firestore would read a `/` as a deeper path. */
  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }

  companion object {
    const val USERS_COLLECTION = "users"
    const val COLLECTION = "eventRegistrations"
    const val FIELD_CONFIRMED_AT = "confirmedAt"
    const val FIELD_ADDED_BY = "addedBy"
    const val FIELD_ADDED_BY_NAME = "addedByName"
    private const val TAG = "EventRegistrationRepo"
  }
}
