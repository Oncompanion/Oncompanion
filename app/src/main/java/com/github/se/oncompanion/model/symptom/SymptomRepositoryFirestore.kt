// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import android.util.Log
import com.github.se.oncompanion.model.session.SessionAccess
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [SymptomRepository] backed by Cloud Firestore, documents at `/users/{uid}/symptoms/{id}`.
 *
 * Like [com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore], writes return as soon
 * as they are applied to the local cache, so logging a symptom never waits for the network. If the
 * server later rejects a write, Firestore rolls it back and the rejection is logged;
 * [SymptomEntry.isValid] mirrors the rules so this shouldn't happen.
 */
class SymptomRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
) : SymptomRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeSymptoms(uid: String): Flow<List<SymptomEntry>> = SessionAccess.snapshots {
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

  override fun observeSymptom(uid: String, id: String): Flow<SymptomEntry?> =
      SessionAccess.snapshots {
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

  override suspend fun addSymptom(uid: String, entry: SymptomEntry): String =
      SessionAccess.operation {
        require(entry.isValid()) { "Invalid symptom entry" }
        // The ID is generated on the device, so it is known right away, also offline
        val document = collection(uid).document()
        document
            .set(fieldsOf(entry) + (FIELD_CREATED_AT to FieldValue.serverTimestamp()))
            .addOnFailureListener { e ->
              Log.e(TAG, "The server rejected the symptom ${document.id}; it was rolled back", e)
            }
        return@operation document.id
      }

  override suspend fun updateSymptom(uid: String, entry: SymptomEntry): Unit =
      SessionAccess.operation {
        require(entry.id.isNotBlank()) { "The symptom to update has no ID" }
        require(entry.isValid()) { "Invalid symptom entry" }
        // update() only changes an existing document, unlike set(); createdAt is left untouched.
        // Not
        // awaited, like deleteSymptom
        collection(uid).document(entry.id).update(fieldsOf(entry)).addOnFailureListener { e ->
          Log.e(
              TAG,
              "Couldn't update the symptom ${entry.id} (deleted or rejected); rolled back",
              e,
          )
        }
      }

  override suspend fun deleteSymptom(uid: String, id: String): Unit = SessionAccess.operation {
    // Not awaited: the task only completes once the server confirms, which never happens offline
    collection(uid).document(id).delete().addOnFailureListener { e ->
      Log.e(TAG, "The server rejected deleting the symptom $id; it was restored", e)
    }
  }

  /** What the user entered, as stored in Firestore (everything except the ID and createdAt). */
  private fun fieldsOf(entry: SymptomEntry): Map<String, Any?> =
      mapOf(
          FIELD_TYPES to entry.types.map { it.name },
          FIELD_INTENSITY to entry.intensity,
          FIELD_OCCURRED_AT to Timestamp(entry.occurredAt),
          FIELD_NOTES to entry.notes,
      )

  private fun collection(uid: String) =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  private fun fromDocument(doc: DocumentSnapshot): SymptomEntry? {
    if (!doc.exists()) return null
    return try {
      SymptomEntry(
          id = doc.id,
          // Entries saved before several symptoms could be logged have no types: they are skipped
          types = (doc.get(FIELD_TYPES) as List<*>).map { SymptomType.valueOf(it as String) },
          intensity = doc.getLong(FIELD_INTENSITY)?.toInt(),
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
    const val FIELD_TYPES = "types"
    const val FIELD_INTENSITY = "intensity"
    const val FIELD_OCCURRED_AT = "occurredAt"
    const val FIELD_NOTES = "notes"
    const val FIELD_CREATED_AT = "createdAt"
    private const val TAG = "SymptomRepository"
  }
}
