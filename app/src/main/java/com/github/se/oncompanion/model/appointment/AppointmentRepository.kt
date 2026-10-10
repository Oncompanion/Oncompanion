package com.github.se.oncompanion.model.appointment

import kotlinx.coroutines.flow.Flow

/**
 * Reads and creates a patient's appointments, independently of Firebase. Callers resolve the
 * patient uid and cancel their observations when the account changes.
 */
interface AppointmentRepository {
  /**
   * Emits locally available appointments for [uid], then each change, ordered by scheduled time
   * ascending and by ID for equal times. An empty cache can initially emit an empty list. Read
   * failures propagate through the flow; stopping collection releases its listener.
   *
   * @throws IllegalArgumentException if [uid] is blank or contains a path separator
   */
  fun observeAppointments(uid: String): Flow<List<Appointment>>

  /**
   * Returns appointment [id] for [uid], or null when it does not exist. Cached appointments remain
   * readable offline. An uncached document may fail to load offline; a read failure is thrown,
   * never reported as a missing appointment.
   *
   * @throws IllegalArgumentException if [uid] or [id] is blank or contains a path separator
   */
  suspend fun getAppointment(uid: String, id: String): Appointment?

  /**
   * Enqueues a new appointment for [uid] and returns its generated ID without waiting for server
   * acknowledgement, including offline. The supplied ID and creation timestamp are ignored.
   *
   * Validation and immediate enqueue failures are thrown. A later server rejection is logged by the
   * storage implementation and may roll back the locally visible entry; returning an ID does not
   * confirm server acceptance or an independently verified disk flush.
   *
   * @throws IllegalArgumentException if the appointment is invalid, or [uid] is blank or contains a
   *   path separator
   */
  suspend fun addAppointment(uid: String, appointment: Appointment): String
}
