package com.github.se.oncompanion.model.appointment

import java.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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

  /** When set, a newly collected appointment observation fails with this exception. */
  var observeError: Exception? = null

  /** When set, a single-appointment read fails with this exception. */
  var readError: Exception? = null

  /** When set, adding an appointment fails without changing storage. */
  var writeError: Exception? = null

  /** Returns a snapshot of [uid]'s stored appointments, without imposing an order. */
  fun appointments(uid: String): List<Appointment> = store.value[uid].orEmpty().values.toList()

  /**
   * Inserts or replaces [appointments] with their supplied IDs, bypassing validation for test
   * setup.
   */
  fun seed(uid: String, vararg appointments: Appointment) {
    store.update { current ->
      current + (uid to (current[uid].orEmpty() + appointments.associateBy { it.id }))
    }
  }

  override fun observeAppointments(uid: String): Flow<List<Appointment>> {
    requirePathSegment(uid)
    return flow {
      observeError?.let { throw it }
      emitAll(
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
              .distinctUntilChanged()
      )
    }
  }

  override suspend fun getAppointment(uid: String, id: String): Appointment? {
    requirePathSegment(uid)
    requirePathSegment(id)
    readError?.let { throw it }
    return store.value[uid]?.get(id)
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

  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }
}
