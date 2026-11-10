// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the prescriptions of a user and their medications. A prescription and its
 * medications are always written and deleted together.
 *
 * Every function takes the [uid] of the patient the data belongs to. Reads work offline from the
 * local cache, and writes return as soon as they are saved on the device (also offline); they reach
 * the server when there is a connection. If the server later rejects one, the change is undone on
 * the device and reported through [com.github.se.oncompanion.model.session.RejectedWrites], which
 * tells the user.
 */
interface MedicationRepository {

  /** A new unique ID for a [Prescription] or a [Medication]. Works offline. */
  fun newId(): String

  /**
   * Emits the prescriptions of [uid] now and every time they change, most recently prescribed
   * first. Each one has its medications in the order they were saved.
   */
  fun observePrescriptions(uid: String): Flow<List<Prescription>>

  /**
   * Emits the prescription [id] of [uid] with its medications (or `null` if it doesn't exist) now
   * and every time it changes. Offline, also `null` if this device has never loaded it.
   */
  fun observePrescription(uid: String, id: String): Flow<Prescription?>

  /**
   * Emits the medications of [uid]'s confirmed prescriptions ([confirmedMedications]) now and every
   * time they change, for features that don't need the prescriptions, such as reminders: one a
   * caregiver added never schedules anything before the patient confirms it (US-43).
   */
  fun observeMedications(uid: String): Flow<List<Medication>>

  /**
   * Saves a new prescription with its medications. [Prescription.createdAt] is ignored and set by
   * the server.
   *
   * @throws IllegalArgumentException if the prescription is not [Prescription.isValid]
   */
  suspend fun addPrescription(uid: String, prescription: Prescription)

  /**
   * Replaces the content of an existing prescription: its medications become exactly
   * [Prescription.medications], so the ones left out are deleted. [Prescription.createdAt] never
   * changes. Does nothing if the prescription doesn't exist. Offline, a device that has never
   * loaded the prescription can't tell: the update is kept and sent when the connection comes back,
   * and nothing is saved if the prescription doesn't exist.
   *
   * @throws IllegalArgumentException if the prescription is not [Prescription.isValid]
   */
  suspend fun updatePrescription(uid: String, prescription: Prescription)

  /**
   * Deletes the prescription [id] of [uid] and its medications. Does nothing if it doesn't exist.
   */
  suspend fun deletePrescription(uid: String, id: String)
}
