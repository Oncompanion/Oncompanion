package com.github.se.oncompanion.model.symptom

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [SymptomRepository] backed by Cloud Firestore, documents at `/users/{uid}/symptoms/{id}`.
 *
 * Like [com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore], writes return as soon
 * as they are applied to the local cache, so logging a symptom never waits for the network. If the
 * server later rejects a write, Firestore rolls it back and the rejection is logged;
 * [SymptomEntry.isValid] mirrors the rules so this shouldn't happen.
 */
class SymptomRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : SymptomRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun observeSymptoms(uid: String): Flow<List<SymptomEntry>> = callbackFlow {
    val registration =
        collection(uid)
            .orderBy(FIELD_OCCURRED_AT, Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else {
                trySend(snapshot?.documents?.mapNotNull(::fromDocument).orEmpty())
              }
            }
    awaitClose { registration.remove() }
  }

  override fun observeSymptom(uid: String, id: String): Flow<SymptomEntry?> = callbackFlow {
    val registration =
        collection(uid).document(id).addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
          } else {
            trySend(snapshot?.let(::fromDocument))
          }
        }
    awaitClose { registration.remove() }
  }

  override suspend fun addSymptom(uid: String, entry: SymptomEntry): String {
    require(entry.isValid()) { "Invalid symptom entry" }
    // The ID is generated on the device, so it is known right away, also offline
    val document = collection(uid).document()
    document
        .set(
            mapOf(
                FIELD_TYPE to entry.type.name,
                FIELD_OTHER_LABEL to entry.otherLabel,
                FIELD_INTENSITY to entry.intensity,
                FIELD_OCCURRED_AT to Timestamp(entry.occurredAt),
                FIELD_NOTES to entry.notes,
                FIELD_CREATED_AT to FieldValue.serverTimestamp(),
            )
        )
        .addOnFailureListener { e ->
          Log.e(TAG, "The server rejected the symptom ${document.id}; it was rolled back", e)
        }
    return document.id
  }

  private fun collection(uid: String) =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  private fun fromDocument(doc: DocumentSnapshot): SymptomEntry? {
    if (!doc.exists()) return null
    return try {
      SymptomEntry(
          id = doc.id,
          type = SymptomType.valueOf(doc.getString(FIELD_TYPE)!!),
          otherLabel = doc.getString(FIELD_OTHER_LABEL),
          intensity = doc.getLong(FIELD_INTENSITY)!!.toInt(),
          occurredAt = doc.getTimestamp(FIELD_OCCURRED_AT)!!.toInstant(),
          notes = doc.getString(FIELD_NOTES),
          // Pending server timestamps read as null until the write reaches the server
          createdAt = doc.getTimestamp(FIELD_CREATED_AT)?.toInstant(),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Malformed symptom document ${doc.id}", e)
      null
    }
  }

  companion object {
    const val USERS_COLLECTION = "users"
    const val COLLECTION = "symptoms"
    const val FIELD_TYPE = "type"
    const val FIELD_OTHER_LABEL = "otherLabel"
    const val FIELD_INTENSITY = "intensity"
    const val FIELD_OCCURRED_AT = "occurredAt"
    const val FIELD_NOTES = "notes"
    const val FIELD_CREATED_AT = "createdAt"
    private const val TAG = "SymptomRepository"
  }
}
