package com.github.se.oncompanion.model.medication

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * [MedicationRepository] backed by Cloud Firestore: what a prescription's medications share is at
 * `/users/{uid}/prescriptions/{id}`, and each medication at `/users/{uid}/medications/{id}`.
 *
 * A prescription and its medications are written in one batch, so they are saved together or not at
 * all. Writes return as soon as they are applied to the local cache, without waiting for the
 * server, so they never block the UI (offline, Firestore syncs them when the connection comes
 * back). What an update or a deletion needs to know first (whether the prescription was deleted,
 * which medications it has) is also taken from the local cache. If the server later rejects a write
 * (security rules, or an update of a prescription that doesn't exist), Firestore rolls the local
 * change back and the rejection is logged; [Prescription.isValid] mirrors the rules so the former
 * shouldn't happen.
 */
class MedicationRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : MedicationRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun newId(): String = db.collection(USERS).document().id

  override fun observePrescriptions(uid: String): Flow<List<Prescription>> =
      combine(prescriptions(uid).snapshots(), medications(uid).snapshots()) {
          prescriptions,
          medications ->
        val medicationsByPrescription = medications.groupBy { it.getString(FIELD_PRESCRIPTION_ID) }
        prescriptions
            .mapNotNull { toPrescription(it, medicationsByPrescription[it.id].orEmpty()) }
            // The two collections are listened to separately: a prescription can show up a moment
            // before its medications, or stay a moment after them. It is left out meanwhile.
            .filter { it.medications.isNotEmpty() }
            .sortedWith(
                compareByDescending<Prescription> { it.prescribedOn }
                    // Not confirmed by the server yet: counts as the most recently saved
                    .thenByDescending { it.createdAt ?: Instant.MAX }
            )
      }

  override suspend fun getPrescription(uid: String, id: String): Prescription? {
    val prescription = existingPrescription(uid, id) ?: return null
    return toPrescription(prescription, medicationsOf(uid, id))
  }

  override fun observeMedications(uid: String): Flow<List<Medication>> =
      medications(uid).snapshots().map { documents ->
        documents.mapNotNull(::toMedication).sortedWith(compareBy({ it.startDate }, { it.name }))
      }

  override suspend fun addPrescription(uid: String, prescription: Prescription) {
    require(prescription.isValid()) { "Invalid prescription" }
    val batch = db.batch()
    batch.set(
        prescriptions(uid).document(prescription.id),
        prescriptionFields(prescription) + (FIELD_CREATED_AT to FieldValue.serverTimestamp()),
    )
    prescription.medications.forEachIndexed { position, medication ->
      batch.set(medications(uid).document(medication.id), medicationFields(medication, position))
    }
    batch.commit().logServerRejection(prescription.id)
  }

  override suspend fun updatePrescription(uid: String, prescription: Prescription) {
    require(prescription.isValid()) { "Invalid prescription" }
    if (isKnownAsDeleted(uid, prescription.id)) return
    val keptIds = prescription.medications.map { it.id }.toSet()
    val batch = db.batch()
    // update() and not set(): createdAt stays as it is, and the server rejects the whole batch if
    // the prescription doesn't exist
    batch.update(prescriptions(uid).document(prescription.id), prescriptionFields(prescription))
    prescription.medications.forEachIndexed { position, medication ->
      batch.set(medications(uid).document(medication.id), medicationFields(medication, position))
    }
    cachedMedicationsOf(uid, prescription.id)
        .filter { it.id !in keptIds }
        .forEach { batch.delete(it.reference) }
    batch.commit().logServerRejection(prescription.id)
  }

  override suspend fun deletePrescription(uid: String, id: String) {
    val batch = db.batch()
    batch.delete(prescriptions(uid).document(id))
    cachedMedicationsOf(uid, id).forEach { batch.delete(it.reference) }
    batch.commit().logServerRejection(id)
  }

  private fun prescriptions(uid: String) =
      db.collection(USERS).document(uid).collection(PRESCRIPTIONS)

  private fun medications(uid: String) = db.collection(USERS).document(uid).collection(MEDICATIONS)

  /**
   * The document of prescription [id], or `null` if it doesn't exist, or if the device is offline
   * and has never loaded it.
   */
  private suspend fun existingPrescription(uid: String, id: String): DocumentSnapshot? =
      try {
        prescriptions(uid).document(id).get().await().takeIf { it.exists() }
      } catch (e: FirebaseFirestoreException) {
        // Offline, Firestore fails on a document it has never seen: there is nothing to return
        if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) null else throw e
      }

  /**
   * Whether this device knows that prescription [id] doesn't exist (e.g. it was deleted). Only
   * looks at the local cache, so it never waits for the network. `false` if the device has never
   * loaded the prescription: it may exist on the server, which then decides.
   */
  private suspend fun isKnownAsDeleted(uid: String, id: String): Boolean =
      try {
        !prescriptions(uid).document(id).get(Source.CACHE).await().exists()
      } catch (e: FirebaseFirestoreException) {
        // Not in the cache: Firestore can't tell whether it exists
        if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) false else throw e
      }

  /** The documents of the medications of prescription [id], in any order. */
  private suspend fun medicationsOf(uid: String, id: String): List<DocumentSnapshot> =
      medications(uid).whereEqualTo(FIELD_PRESCRIPTION_ID, id).get().await().documents

  /**
   * The medications of prescription [id] that this device has in its local cache, without waiting
   * for the network.
   */
  private suspend fun cachedMedicationsOf(uid: String, id: String): List<DocumentSnapshot> =
      medications(uid).whereEqualTo(FIELD_PRESCRIPTION_ID, id).get(Source.CACHE).await().documents

  /** Emits the documents of the collection now and every time they change. */
  private fun CollectionReference.snapshots(): Flow<List<DocumentSnapshot>> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
      if (error != null) {
        close(error)
      } else if (snapshot != null) {
        trySend(snapshot.documents)
      }
    }
    awaitClose { registration.remove() }
  }

  private fun Task<Void>.logServerRejection(prescriptionId: String) {
    addOnFailureListener { e ->
      Log.e(TAG, "The server rejected prescription $prescriptionId; the change was rolled back", e)
    }
  }

  // A calendar day is stored as the Timestamp of its midnight in Switzerland. Both directions use
  // ZONE and never the phone's time zone, so the day read back is always the day that was saved.
  private fun LocalDate.toTimestamp() = Timestamp(atStartOfDay(ZONE).toInstant())

  private fun Timestamp.toLocalDate(): LocalDate = toInstant().atZone(ZONE).toLocalDate()

  /** The fields of the prescription document, except `createdAt`. */
  private fun prescriptionFields(prescription: Prescription): Map<String, Any?> =
      mapOf(
          FIELD_PRESCRIBED_BY to prescription.prescribedBy,
          FIELD_PRESCRIBED_ON to prescription.prescribedOn.toTimestamp(),
      )

  private fun medicationFields(medication: Medication, position: Int): Map<String, Any?> =
      mapOf(
          FIELD_PRESCRIPTION_ID to medication.prescriptionId,
          FIELD_NAME to medication.name,
          FIELD_DOSAGE to medication.dosage,
          FIELD_FREQUENCY to medication.frequency,
          FIELD_START_DATE to medication.startDate.toTimestamp(),
          FIELD_DURATION_DAYS to medication.durationDays,
          FIELD_POSITION to position,
      )

  /** Builds a prescription from its document and the documents of its medications, in any order. */
  private fun toPrescription(doc: DocumentSnapshot, medications: List<DocumentSnapshot>) =
      try {
        Prescription(
            id = doc.id,
            prescribedBy = doc.getString(FIELD_PRESCRIBED_BY),
            prescribedOn = doc.getTimestamp(FIELD_PRESCRIBED_ON)!!.toLocalDate(),
            medications =
                medications.sortedBy { it.getLong(FIELD_POSITION) ?: 0 }.mapNotNull(::toMedication),
            // Pending server timestamps read as null until the write reaches the server
            createdAt = doc.getTimestamp(FIELD_CREATED_AT)?.let(Timestamp::toInstant),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Malformed prescription document ${doc.id}", e)
        null
      }

  private fun toMedication(doc: DocumentSnapshot): Medication? =
      try {
        Medication(
            id = doc.id,
            prescriptionId = doc.getString(FIELD_PRESCRIPTION_ID)!!,
            name = doc.getString(FIELD_NAME)!!,
            dosage = doc.getString(FIELD_DOSAGE),
            frequency = doc.getString(FIELD_FREQUENCY),
            startDate = doc.getTimestamp(FIELD_START_DATE)!!.toLocalDate(),
            durationDays = doc.getLong(FIELD_DURATION_DAYS)?.toInt(),
        )
      } catch (e: Exception) {
        Log.e(TAG, "Malformed medication document ${doc.id}", e)
        null
      }

  companion object {
    const val USERS = "users"
    const val PRESCRIPTIONS = "prescriptions"
    const val MEDICATIONS = "medications"

    // Prescription fields
    const val FIELD_PRESCRIBED_BY = "prescribedBy"
    const val FIELD_PRESCRIBED_ON = "prescribedOn"
    const val FIELD_CREATED_AT = "createdAt"

    // Medication fields
    const val FIELD_PRESCRIPTION_ID = "prescriptionId"
    const val FIELD_NAME = "name"
    const val FIELD_DOSAGE = "dosage"
    const val FIELD_FREQUENCY = "frequency"
    const val FIELD_START_DATE = "startDate"
    const val FIELD_DURATION_DAYS = "durationDays"
    /**
     * Rank of the medication in its prescription, to read them back in the order they were saved.
     */
    const val FIELD_POSITION = "position"

    /**
     * The time zone used to store calendar days, see [FIELD_PRESCRIBED_ON] and [FIELD_START_DATE].
     */
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

    private const val TAG = "MedicationRepository"
  }
}
