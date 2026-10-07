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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * [MedicationRepository] backed by Cloud Firestore: a prescription is one document at
 * `/users/{uid}/prescriptions/{id}` that holds the list of its medications.
 *
 * Saving, replacing or deleting a prescription is therefore a single write: its medications can't
 * be half saved, and when two devices replace them, the last save to reach the server replaces the
 * whole list. Writes return as soon as they are applied to the local cache, without waiting for the
 * server, so they never block the UI (offline, Firestore syncs them when the connection comes
 * back). Whether a prescription was deleted, which an update needs to know first, is also taken
 * from the local cache. If the server later rejects a write (security rules, or an update of a
 * prescription that doesn't exist), Firestore rolls the local change back and the rejection is
 * logged; [Prescription.isValid] mirrors the rules so the former shouldn't happen.
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
      prescriptions(uid).snapshots().map { documents ->
        documents
            .mapNotNull(::toPrescription)
            .sortedWith(
                compareByDescending<Prescription> { it.prescribedOn }
                    // Not confirmed by the server yet: counts as the most recently saved
                    .thenByDescending { it.createdAt ?: Instant.MAX }
            )
      }

  override suspend fun getPrescription(uid: String, id: String): Prescription? =
      existingPrescription(uid, id)?.let(::toPrescription)

  override fun observeMedications(uid: String): Flow<List<Medication>> =
      prescriptions(uid).snapshots().map { documents ->
        documents
            .mapNotNull(::toPrescription)
            .flatMap { it.medications }
            .sortedWith(compareBy({ it.startDate }, { it.name }))
      }

  override suspend fun addPrescription(uid: String, prescription: Prescription) {
    require(prescription.isValid()) { "Invalid prescription" }
    prescriptions(uid)
        .document(prescription.id)
        .set(prescriptionFields(prescription) + (FIELD_CREATED_AT to FieldValue.serverTimestamp()))
        .logServerRejection(prescription.id)
  }

  override suspend fun updatePrescription(uid: String, prescription: Prescription) {
    require(prescription.isValid()) { "Invalid prescription" }
    if (isKnownAsDeleted(uid, prescription.id)) return
    // update() and not set(): createdAt stays as it is, and the server rejects the write if the
    // prescription doesn't exist. The list of medications is replaced as a whole.
    prescriptions(uid)
        .document(prescription.id)
        .update(prescriptionFields(prescription))
        .logServerRejection(prescription.id)
  }

  override suspend fun deletePrescription(uid: String, id: String) {
    prescriptions(uid).document(id).delete().logServerRejection(id)
  }

  private fun prescriptions(uid: String) =
      db.collection(USERS).document(uid).collection(PRESCRIPTIONS)

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
        if (e.code != FirebaseFirestoreException.Code.UNAVAILABLE) throw e
        // Not in the cache: Firestore can't tell whether it exists
        false
      }

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
          FIELD_MEDICATIONS to prescription.medications.map(::medicationFields),
      )

  /** One entry of the list of medications; its prescription is the document it is stored in. */
  private fun medicationFields(medication: Medication): Map<String, Any?> =
      mapOf(
          FIELD_ID to medication.id,
          FIELD_NAME to medication.name,
          FIELD_DOSAGE to medication.dosage,
          FIELD_FREQUENCY to medication.frequency,
          FIELD_START_DATE to medication.startDate.toTimestamp(),
          FIELD_DURATION_DAYS to medication.durationDays,
      )

  /**
   * Builds a prescription from its document. The security rules don't check the fields of each
   * medication, so they are checked here: a medication that can't be read or is not
   * [Medication.isValid] is left out, and so is one that repeats the ID of an earlier one. `null`
   * if the document itself can't be read or none of its medications is kept.
   */
  private fun toPrescription(doc: DocumentSnapshot): Prescription? =
      try {
        Prescription(
                id = doc.id,
                prescribedBy = doc.getString(FIELD_PRESCRIBED_BY),
                prescribedOn = doc.getTimestamp(FIELD_PRESCRIBED_ON)!!.toLocalDate(),
                medications =
                    (doc.get(FIELD_MEDICATIONS) as List<*>)
                        .mapNotNull { toMedication(doc.id, it) }
                        .distinctBy { it.id },
                // Pending server timestamps read as null until the write reaches the server
                createdAt = doc.getTimestamp(FIELD_CREATED_AT)?.let(Timestamp::toInstant),
            )
            .takeIf { it.medications.isNotEmpty() }
      } catch (e: Exception) {
        Log.e(TAG, "Malformed prescription document ${doc.id}", e)
        null
      }

  /**
   * Builds a medication from one entry of the list of medications of [prescriptionId], or `null` if
   * the entry can't be read or its values are not [Medication.isValid].
   */
  private fun toMedication(prescriptionId: String, entry: Any?): Medication? =
      try {
        val fields = entry as Map<*, *>
        Medication(
                id = fields[FIELD_ID] as String,
                prescriptionId = prescriptionId,
                name = fields[FIELD_NAME] as String,
                dosage = fields[FIELD_DOSAGE] as String?,
                frequency = fields[FIELD_FREQUENCY] as String?,
                startDate = (fields[FIELD_START_DATE] as Timestamp).toLocalDate(),
                // Only a whole number that fits in an Int: converting a decimal or a bigger number
                // would turn it into a duration that looks valid
                durationDays = (fields[FIELD_DURATION_DAYS] as Long?)?.let(Math::toIntExact),
            )
            .also { require(it.isValid()) { "Invalid values" } }
      } catch (e: Exception) {
        Log.e(TAG, "Malformed medication in prescription document $prescriptionId", e)
        null
      }

  companion object {
    const val USERS = "users"
    const val PRESCRIPTIONS = "prescriptions"

    // Prescription fields
    const val FIELD_PRESCRIBED_BY = "prescribedBy"
    const val FIELD_PRESCRIBED_ON = "prescribedOn"
    const val FIELD_CREATED_AT = "createdAt"
    /** The medications of the prescription, in the order they were saved. */
    const val FIELD_MEDICATIONS = "medications"

    // Fields of each medication of the list
    const val FIELD_ID = "id"
    const val FIELD_NAME = "name"
    const val FIELD_DOSAGE = "dosage"
    const val FIELD_FREQUENCY = "frequency"
    const val FIELD_START_DATE = "startDate"
    const val FIELD_DURATION_DAYS = "durationDays"

    /**
     * The time zone used to store calendar days, see [FIELD_PRESCRIBED_ON] and [FIELD_START_DATE].
     */
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")

    private const val TAG = "MedicationRepository"
  }
}
