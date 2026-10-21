// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.appointment

import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes a patient's appointments, independently of Firebase. Callers resolve the patient
 * uid and cancel their observations when the account changes.
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
   * Emits the locally available appointment [id] for [uid], then each change. Emits null when the
   * snapshot has no readable appointment, including missing or malformed documents and an empty
   * offline cache; null does not confirm server-side deletion. Cached appointments remain available
   * offline. Listener failures propagate through the flow; stopping collection releases its
   * listener.
   *
   * @throws IllegalArgumentException if [uid] or [id] is blank or contains a path separator
   */
  fun observeAppointment(uid: String, id: String): Flow<Appointment?>

  /**
   * Enqueues changes to the existing [appointment] for [uid], preserving its stored creation time.
   * Returns without waiting for server acknowledgement, including offline. An absent document is
   * never created; its deferred update rejection is logged, not thrown to the caller.
   *
   * Validation and immediate enqueue failures are thrown. Later server rejection may roll back
   * locally visible changes; completion does not confirm server acceptance or a disk flush.
   *
   * @throws IllegalArgumentException if editable fields are invalid, or [uid] or the appointment ID
   *   is blank or contains a path separator
   */
  suspend fun updateAppointment(uid: String, appointment: Appointment)

  /**
   * Enqueues deletion of appointment [id] for [uid], including offline. Deleting an absent document
   * is harmless. Returns without server acknowledgement or an independently verified disk flush.
   * Immediate enqueue failures are thrown; deferred server rejection is logged and may restore the
   * locally removed appointment.
   *
   * @throws IllegalArgumentException if [uid] or [id] is blank or contains a path separator
   */
  suspend fun deleteAppointment(uid: String, id: String)

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
