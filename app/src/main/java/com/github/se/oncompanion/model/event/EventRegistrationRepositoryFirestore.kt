// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [EventRegistrationRepository] backed by Cloud Firestore, one document per confirmed event at
 * `/users/{uid}/eventRegistrations/{eventId}`. Keying it by the event makes confirming twice write
 * the same document, so there are never duplicates.
 *
 * Writes return once saved on the phone and sync when the connection returns. If the server rejects
 * one (e.g. the event started before the phone synced), it is logged and Firestore rolls the local
 * write back, which [observeIsRegistered] then emits.
 */
class EventRegistrationRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : EventRegistrationRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun observeIsRegistered(uid: String, eventId: String): Flow<Boolean> {
    val document = document(uid, eventId)
    return callbackFlow {
      val registration = document.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
        } else {
          trySend(snapshot?.exists() == true)
        }
      }
      awaitClose { registration.remove() }
    }
  }

  override fun confirm(uid: String, eventId: String) {
    // The server sets the time, so the rules can check it against the event's start
    document(uid, eventId)
        .set(mapOf(FIELD_CONFIRMED_AT to FieldValue.serverTimestamp()))
        .addOnFailureListener { Log.e(TAG, "Confirmation for event $eventId was rejected", it) }
  }

  override fun remove(uid: String, eventId: String) {
    document(uid, eventId).delete().addOnFailureListener {
      Log.e(TAG, "Removing the confirmation for event $eventId was rejected", it)
    }
  }

  private fun document(uid: String, eventId: String): DocumentReference {
    // Checked before `db` is first used, so a bad id fails the same way with or without Firebase
    requirePathSegment(uid)
    requirePathSegment(eventId)
    return db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION).document(eventId)
  }

  /** Throws unless [value] can be a document id: Firestore would read a `/` as a deeper path. */
  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }

  companion object {
    const val USERS_COLLECTION = "users"
    const val COLLECTION = "eventRegistrations"
    const val FIELD_CONFIRMED_AT = "confirmedAt"
    private const val TAG = "EventRegistrationRepo"
  }
}
