// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.model.appointment

import java.time.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update

/**
 * Shared in-memory appointment storage for unit tests, separated by patient uid. It follows the
 * repository's validation, identity and ordering contract; it does not simulate disk persistence or
 * server acknowledgement.
 *
 * @param clock deterministic creation time used in place of the server timestamp
 */
class FakeAppointmentRepository(private val clock: Clock = Clock.systemUTC()) :
    AppointmentRepository {
  private val store = MutableStateFlow<Map<String, Map<String, Appointment>>>(emptyMap())
  private var nextId = 1
  private val activeFailures = MutableSharedFlow<Exception>(extraBufferCapacity = 1)

  /** Fails active appointment-list listeners without affecting subsequent observations. */
  fun failActiveObservations(error: Exception) {
    check(activeFailures.tryEmit(error)) { "An observation failure is already pending" }
  }

  /** When set, either observation fails with this exception when collection starts. */
  var observeError: Exception? = null

  /** When set, writing an appointment fails without changing storage. */
  var writeError: Exception? = null

  /** Holds writes in flight until a test explicitly completes the gate. */
  var writeGate: CompletableDeferred<Unit>? = null

  /** Owner and appointment ID of each validated deletion request. */
  val deleteRequests = mutableListOf<Pair<String, String>>()

  /** Returns a snapshot of [uid]'s stored appointments, without imposing an order. */
  fun appointments(uid: String): List<Appointment> = store.value[uid].orEmpty().values.toList()

  /**
   * Inserts or replaces [appointments] with their supplied nonblank IDs, bypassing patient-field
   * validation for test setup.
   *
   * @throws IllegalArgumentException if any appointment ID is blank; storage remains unchanged
   */
  fun seed(uid: String, vararg appointments: Appointment) {
    require(appointments.all { it.id.isNotBlank() }) {
      "Seeded appointments must have nonblank IDs"
    }
    store.update { current ->
      current + (uid to (current[uid].orEmpty() + appointments.associateBy { it.id }))
    }
  }

  override fun observeAppointments(uid: String): Flow<List<Appointment>> {
    requirePathSegment(uid)
    return flow {
      observeError?.let { throw it }
      emitAll(
          merge(
              activeFailures.map { throw it },
              store
                  .map {
                    it[uid]
                        .orEmpty()
                        .values
                        .sortedWith(
                            compareBy<Appointment> { entry -> entry.scheduledAt }
                                .thenBy { entry -> entry.id }
                        )
                  }
                  .distinctUntilChanged(),
          )
      )
    }
  }

  override fun observeAppointment(uid: String, id: String): Flow<Appointment?> {
    requirePathSegment(uid)
    requirePathSegment(id)
    return flow {
      observeError?.let { throw it }
      emitAll(store.map { it[uid]?.get(id) }.distinctUntilChanged())
    }
  }

  override suspend fun addAppointment(uid: String, appointment: Appointment): String {
    requirePathSegment(uid)
    require(appointment.isValid()) { "Invalid appointment" }
    writeError?.let { throw it }
    // Seeded fixtures must not be overwritten by a generated ID.
    var id: String
    do {
      id = "appointment-${nextId++}"
    } while (store.value.values.any { id in it })
    seed(uid, appointment.copy(id = id, createdAt = clock.instant()))
    return id
  }

  override suspend fun updateAppointment(uid: String, appointment: Appointment) {
    requirePathSegment(uid)
    requirePathSegment(appointment.id)
    require(appointment.isValid()) { "Invalid appointment" }
    writeError?.let { throw it }
    // An absent record stays absent; this fake does not simulate deferred server rejection.
    store.update { current ->
      val existing = current[uid]?.get(appointment.id)
      if (existing == null) current
      else
          current +
              (uid to
                  (current.getValue(uid) +
                      (appointment.id to appointment.copy(createdAt = existing.createdAt))))
    }
  }

  override suspend fun deleteAppointment(uid: String, id: String) {
    requirePathSegment(uid)
    requirePathSegment(id)
    deleteRequests += uid to id
    writeGate?.await()
    writeError?.let { throw it }
    store.update { current ->
      val appointments = current[uid]
      if (appointments == null || id !in appointments) current
      else current + (uid to (appointments - id))
    }
  }

  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }
}
