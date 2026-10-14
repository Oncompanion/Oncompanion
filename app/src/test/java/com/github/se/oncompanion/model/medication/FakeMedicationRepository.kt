// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/**
 * In-memory [MedicationRepository] for ViewModel and use case unit tests (no Firebase).
 *
 * Follows the contract of [MedicationRepository]:
 * - [addPrescription] requires [Prescription.isValid] (else [IllegalArgumentException]) and stores
 *   the prescription with `createdAt = clock()`, ignoring the given `createdAt`.
 * - [updatePrescription] requires [Prescription.isValid] and replaces everything but `createdAt`.
 *   It does nothing if the prescription doesn't exist.
 * - [deletePrescription] removes the prescription and its medications.
 * - [observePrescriptions], [observePrescription] and [observeMedications] emit the current value,
 *   then every change.
 *
 * Test hooks: [readError], [failRunningReads] and [writeError] simulate unexpected failures,
 * [prescriptionsOf] exposes a snapshot of the stored data, [prescriptionObservers] counts the
 * running [observePrescription] collections, and [newId] returns "id-1", "id-2", ...
 *
 * @param clock source of the `createdAt` timestamp set on creation (the "server time").
 */
class FakeMedicationRepository(private val clock: () -> Instant = Instant::now) :
    MedicationRepository {

  // uid -> prescription ID -> prescription
  private val store = MutableStateFlow<Map<String, Map<String, Prescription>>>(emptyMap())
  private var lastId = 0

  /**
   * When non-null, [observePrescriptions], [observePrescription] and [observeMedications] fail with
   * it (checked when collection starts).
   */
  var readError: Exception? = null

  // Sent to the reads being collected when the test calls failRunningReads
  private val runningReadFailures = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

  /**
   * Makes the reads being collected fail with [error], as Firestore does when it stops a listener
   * (e.g. the user lost access). Reads started afterwards work normally.
   */
  fun failRunningReads(error: Exception) {
    runningReadFailures.tryEmit(error)
  }

  /** How many [observePrescription] collections are running, to check a screen reads only once. */
  var prescriptionObservers = 0
    private set

  /** When non-null, every write throws it and stores nothing. */
  var writeError: Exception? = null

  /** Snapshot of the prescriptions stored for [uid], in the order of [observePrescriptions]. */
  fun prescriptionsOf(uid: String): List<Prescription> = sorted(store.value[uid])

  /** Inserts [prescription] as-is (including its `createdAt`), bypassing validation. Test setup. */
  fun seed(uid: String, prescription: Prescription) {
    put(uid, prescription)
  }

  override fun newId(): String = "id-${++lastId}"

  override fun observePrescriptions(uid: String): Flow<List<Prescription>> =
      read(store.map { sorted(it[uid]) })

  override fun observePrescription(uid: String, id: String): Flow<Prescription?> =
      read(store.map { it[uid]?.get(id) })
          .onStart { prescriptionObservers++ }
          .onCompletion { prescriptionObservers-- }

  override fun observeMedications(uid: String): Flow<List<Medication>> =
      read(
          store.map { all ->
            all[uid]
                .orEmpty()
                .values
                .flatMap { it.medications }
                .sortedWith(compareBy({ it.startDate }, { it.name }))
          }
      )

  override suspend fun addPrescription(uid: String, prescription: Prescription) {
    writeError?.let { throw it }
    require(prescription.isValid()) { "Invalid prescription: $prescription" }
    put(uid, prescription.copy(createdAt = clock()))
  }

  override suspend fun updatePrescription(uid: String, prescription: Prescription) {
    writeError?.let { throw it }
    require(prescription.isValid()) { "Invalid prescription: $prescription" }
    val existing = store.value[uid]?.get(prescription.id) ?: return
    put(uid, prescription.copy(createdAt = existing.createdAt))
  }

  override suspend fun deletePrescription(uid: String, id: String) {
    writeError?.let { throw it }
    store.update { all -> all + (uid to all[uid].orEmpty() - id) }
  }

  /**
   * Emits [values] when they change, failing with [readError] when collection starts and with what
   * [failRunningReads] sends while it runs.
   */
  private fun <T> read(values: Flow<T>): Flow<T> = flow {
    readError?.let { throw it }
    emitAll(
        merge(values.distinctUntilChanged(), runningReadFailures.map<Exception, T> { throw it })
    )
  }

  private fun put(uid: String, prescription: Prescription) {
    store.update { all -> all + (uid to all[uid].orEmpty() + (prescription.id to prescription)) }
  }

  // Most recently prescribed first, then most recently saved
  private fun sorted(prescriptions: Map<String, Prescription>?): List<Prescription> =
      prescriptions
          .orEmpty()
          .values
          .sortedWith(
              compareByDescending<Prescription> { it.prescribedOn }
                  .thenByDescending { it.createdAt ?: Instant.MAX }
          )
}
