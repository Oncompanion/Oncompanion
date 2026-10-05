package com.github.se.oncompanion.model.medication

import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the prescriptions of a user and their medications. A prescription and its
 * medications are always written and deleted together.
 *
 * Every function takes the [uid] of the patient the data belongs to. Reads work offline from the
 * local cache, and writes return as soon as they are saved on the device (also offline); they reach
 * the server when there is a connection.
 */
interface MedicationRepository {

  /** A new unique ID for a [Prescription] or a [Medication]. Works offline. */
  fun newId(): String

  /**
   * Emits the prescriptions of [uid] now and every time they change, most recently prescribed
   * first. Each one has its medications in the order they were saved.
   */
  fun observePrescriptions(uid: String): Flow<List<Prescription>>

  /** Returns the prescription [id] of [uid] with its medications, or `null` if it doesn't exist. */
  suspend fun getPrescription(uid: String, id: String): Prescription?

  /**
   * Emits all the medications of [uid] now and every time they change, earliest start date first
   * then by name, for features that don't need the prescriptions (e.g. Planning).
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
   * changes. Does nothing if the prescription doesn't exist.
   *
   * @throws IllegalArgumentException if the prescription is not [Prescription.isValid]
   */
  suspend fun updatePrescription(uid: String, prescription: Prescription)

  /**
   * Deletes the prescription [id] of [uid] and its medications. Does nothing if it doesn't exist.
   */
  suspend fun deletePrescription(uid: String, id: String)
}
