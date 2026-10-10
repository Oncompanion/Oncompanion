package com.github.se.oncompanion.model.appointment

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Appointment storage at `/users/{uid}/appointments/{id}`, using Firestore's offline cache. Writes
 * are enqueued without awaiting server acknowledgement. A later server rejection is logged and
 * Firestore rolls back the local write; returned IDs do not confirm server acceptance.
 */
class AppointmentRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : AppointmentRepository {
  /** Uses the supplied Firestore instance, allowing tests to connect to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Default ViewModel construction must also work before Firebase is initialized in unit tests.
  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun observeAppointments(uid: String): Flow<List<Appointment>> {
    requirePathSegment(uid)
    return callbackFlow {
      val registration =
          collection(uid)
              .orderBy(FIELD_SCHEDULED_AT, Query.Direction.ASCENDING)
              .addSnapshotListener { snapshot, error ->
                if (error != null) {
                  close(error)
                } else {
                  val appointments = snapshot?.documents?.mapNotNull(::fromDocument).orEmpty()
                  trySend(
                      appointments.sortedWith(
                          compareBy<Appointment> { it.scheduledAt }.thenBy { it.id }
                      )
                  )
                }
              }
      awaitClose { registration.remove() }
    }
  }

  override suspend fun getAppointment(uid: String, id: String): Appointment? {
    requirePathSegment(uid)
    requirePathSegment(id)
    // The default source falls back to cached data offline; uncached read errors propagate.
    return fromDocument(collection(uid).document(id).get().await())
  }

  override suspend fun addAppointment(uid: String, appointment: Appointment): String {
    requirePathSegment(uid)
    require(appointment.isValid()) { "Invalid appointment" }
    // Generate a fresh ID locally, ignoring any identity or creation time supplied by the caller.
    val document = collection(uid).document()
    document
        .set(
            mapOf(
                FIELD_TITLE to appointment.title,
                FIELD_SCHEDULED_AT to Timestamp(appointment.scheduledAt),
                FIELD_TYPE to appointment.type.name,
                FIELD_LOCATION to appointment.location,
                FIELD_NOTES to appointment.notes,
                FIELD_CREATED_AT to FieldValue.serverTimestamp(),
            )
        )
        .addOnFailureListener { error ->
          Log.e(
              TAG,
              "Appointment write ${document.id} was rejected; the local write was rolled back",
              error,
          )
        }
    return document.id
  }

  private fun collection(uid: String) =
      db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }

  private fun fromDocument(document: DocumentSnapshot): Appointment? {
    if (!document.exists()) return null
    return try {
      val createdAt = document.getTimestamp(FIELD_CREATED_AT)?.toInstant()
      // Only an unresolved local server timestamp may be null in a stored appointment.
      require(document.contains(FIELD_CREATED_AT))
      require(createdAt != null || document.metadata.hasPendingWrites())
      Appointment(
              id = document.id,
              title = requireNotNull(document.getString(FIELD_TITLE)),
              scheduledAt = requireNotNull(document.getTimestamp(FIELD_SCHEDULED_AT)).toInstant(),
              type = AppointmentType.valueOf(requireNotNull(document.getString(FIELD_TYPE))),
              location = document.getString(FIELD_LOCATION),
              notes = document.getString(FIELD_NOTES),
              createdAt = createdAt,
          )
          .also { require(it.isValid()) }
    } catch (_: RuntimeException) {
      // Keep malformed records out of the agenda without logging their personal field values.
      Log.e(TAG, "Malformed appointment document ${document.id}")
      null
    }
  }

  companion object {
    /** Parent collection for all patient-owned data. */
    const val USERS_COLLECTION = "users"
    /** Appointment collection below each patient. */
    const val COLLECTION = "appointments"
    /** Patient-entered appointment title. */
    const val FIELD_TITLE = "title"
    /** Actual appointment instant, stored as a timestamp. */
    const val FIELD_SCHEDULED_AT = "scheduledAt"
    /** Recorded category, stored as the enum name. */
    const val FIELD_TYPE = "type"
    /** Optional location text. */
    const val FIELD_LOCATION = "location"
    /** Optional patient notes. */
    const val FIELD_NOTES = "notes"
    /** Server-assigned creation timestamp. */
    const val FIELD_CREATED_AT = "createdAt"
    private const val TAG = "AppointmentRepository"
  }
}
