// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import android.util.Log
import com.github.se.oncompanion.model.medication.TakenDosesRepository.Companion.MAX_TAKEN_PER_DAY
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectedWritesProvider
import com.github.se.oncompanion.model.session.SessionAccess
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.time.LocalDate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [TakenDosesRepository] backed by Cloud Firestore: one document per day at
 * `/users/{uid}/takenDoses/{yyyy-MM-dd}`, `{taken: {<medicationId>: count}, updatedAt}`.
 *
 * A count rather than one document per tick: unticking is one less, and the Overview only needs the
 * number. Writes give the count itself, not "one more", so a tick repeated offline can't count
 * twice, and they merge into the day, so ticking one medication never overwrites another. Like the
 * other repositories, a write returns once saved on the device; if the server later rejects it,
 * Firestore undoes it and it is reported to [rejectedWrites].
 */
class TakenDosesRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
    private val rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
) : TakenDosesRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(
      db: FirebaseFirestore,
      rejectedWrites: RejectedWrites = RejectedWritesProvider.rejectedWrites,
  ) : this({ db }, rejectedWrites)

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeDay(uid: String, date: LocalDate): Flow<Map<String, Int>> =
      SessionAccess.snapshots {
        val registration =
            day(uid, date).addSnapshotListener { snapshot, error ->
              if (error != null) close(error) else trySend(snapshot?.let(::takenIn).orEmpty())
            }
        awaitClose { registration.remove() }
      }

  override suspend fun setTaken(uid: String, date: LocalDate, medicationId: String, count: Int) =
      SessionAccess.operation {
        require(medicationId.isNotBlank()) { "The medication has no ID" }
        require(count in 0..MAX_TAKEN_PER_DAY) { "Invalid number of doses taken: $count" }
        // Not awaited: the task only completes once the server confirms, which never happens
        // offline
        day(uid, date)
            .set(
                mapOf(
                    FIELD_TAKEN to mapOf(medicationId to count),
                    FIELD_UPDATED_AT to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            )
            .addOnFailureListener { e ->
              Log.e(TAG, "The server rejected the doses taken on $date; they were rolled back", e)
              rejectedWrites.report()
            }
        Unit
      }

  private fun day(uid: String, date: LocalDate) =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION).document(date.toString())

  /**
   * The counts saved in [doc]. A count that isn't a whole number from 0 to [MAX_TAKEN_PER_DAY], as
   * a newer app version could write, is left out rather than failing the whole day.
   */
  private fun takenIn(doc: DocumentSnapshot): Map<String, Int> {
    val taken = doc.get(FIELD_TAKEN) as? Map<*, *> ?: return emptyMap()
    return taken.entries
        .mapNotNull { (key, value) ->
          val count = (value as? Long)?.takeIf { it in 0..MAX_TAKEN_PER_DAY.toLong() }
          if (key is String && count != null) key to count.toInt()
          else {
            Log.w(TAG, "Malformed count of doses taken on ${doc.id}")
            null
          }
        }
        .toMap()
  }

  companion object {
    const val USERS_COLLECTION = "users"
    const val COLLECTION = "takenDoses"
    const val FIELD_TAKEN = "taken"
    const val FIELD_UPDATED_AT = "updatedAt"
    private const val TAG = "TakenDosesRepository"
  }
}
