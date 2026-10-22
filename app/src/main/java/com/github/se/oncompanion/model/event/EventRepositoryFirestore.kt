// Co-authored-by: Claude Code <noreply@anthropic.com>
package com.github.se.oncompanion.model.event

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * [EventRepository] backed by Cloud Firestore, documents at `/events/{id}`. Events are the same for
 * every user and are added from the Firebase console, so this repository only reads them.
 *
 * Reads go through Firestore's local cache, so events loaded once stay available offline. Times are
 * stored as instants and shown in the phone's time zone.
 *
 * @param clock the current time, to leave out past events
 * @param zone the time zone events are shown in
 */
class EventRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : EventRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(
      db: FirebaseFirestore,
      clock: Clock = Clock.systemUTC(),
      zone: () -> ZoneId = ZoneId::systemDefault,
  ) : this({ db }, clock, zone)

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun getUpcomingEvents(): Flow<List<Event>> = callbackFlow {
    // Filtering and sorting on the same field only needs Firestore's automatic index
    val registration =
        db.collection(COLLECTION)
            .whereGreaterThanOrEqualTo(FIELD_START_AT, Timestamp(clock.instant()))
            .orderBy(FIELD_START_AT, Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else if (snapshot != null) {
                trySend(snapshot.documents.mapNotNull(::fromDocument))
              }
            }
    awaitClose { registration.remove() }
  }

  override suspend fun getEvent(id: String): Event? {
    val doc = db.collection(COLLECTION).document(id).get().await()
    return if (doc.exists()) fromDocument(doc) else null
  }

  /** The event in [doc], or null (logged) if a required field is missing or has the wrong type. */
  private fun fromDocument(doc: DocumentSnapshot): Event? =
      try {
        Event(
            id = doc.id,
            title = doc.getString(FIELD_TITLE)!!,
            place = doc.getString(FIELD_PLACE)!!,
            startDateTime = doc.getTimestamp(FIELD_START_AT)!!.toLocalDateTime(),
            endDateTime = doc.getTimestamp(FIELD_END_AT)?.toLocalDateTime(),
            category = doc.getString(FIELD_CATEGORY),
            summary = doc.getString(FIELD_SUMMARY)!!,
            description = doc.getString(FIELD_DESCRIPTION)!!,
            // Optional: a link of the wrong type is left out instead of hiding the event
            websiteUrl = webLinkOrNull(doc.get(FIELD_WEBSITE_URL) as? String),
        )
      } catch (e: RuntimeException) {
        // One bad document from the console shouldn't hide the other events
        Log.e(TAG, "Malformed event document ${doc.id}, skipped", e)
        null
      }

  private fun Timestamp.toLocalDateTime(): LocalDateTime =
      LocalDateTime.ofInstant(toInstant(), zone())

  companion object {
    const val COLLECTION = "events"
    const val FIELD_TITLE = "title"
    const val FIELD_PLACE = "place"
    const val FIELD_START_AT = "startAt"
    const val FIELD_END_AT = "endAt"
    const val FIELD_CATEGORY = "category"
    const val FIELD_SUMMARY = "summary"
    const val FIELD_DESCRIPTION = "description"
    const val FIELD_WEBSITE_URL = "websiteUrl"
    private const val TAG = "EventRepository"
  }
}
