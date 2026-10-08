package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore

/**
 * Saves a prescription the user has confirmed, with its medications. Every medication write goes
 * through this use case, so that the reminders can later be kept in sync with the stored schedule
 * in one place. There are no reminders yet: for now it only saves.
 */
class ManageMedicationSchedule(
    private val medicationRepository: MedicationRepository = MedicationRepositoryFirestore()
) {

  /**
   * Saves [draft] as a new prescription of [uid]. Returns once it is saved on the device, also
   * offline.
   *
   * @throws IllegalArgumentException if the draft is not [PrescriptionDraft.isValid], or if one of
   *   its medications already has an ID: it comes from a saved prescription, which must be changed,
   *   not added a second time
   */
  suspend operator fun invoke(uid: String, draft: PrescriptionDraft) {
    // Adding a draft made from a saved prescription would leave two prescriptions sharing the
    // same medication IDs, the old one next to the new one
    require(draft.medications.all { it.id == null }) {
      "A new prescription can't contain medications that are already saved: $draft"
    }
    require(draft.isValid()) { "Invalid prescription draft: $draft" }
    // The repository creates the IDs on the device, so this also works offline
    val prescription =
        draft.toPrescription(medicationRepository.newId(), medicationRepository::newId)
    medicationRepository.addPrescription(uid, prescription)
  }
}
