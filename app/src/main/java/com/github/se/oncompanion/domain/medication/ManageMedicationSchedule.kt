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
   * @throws IllegalArgumentException if the draft is not [PrescriptionDraft.isValid]
   */
  suspend operator fun invoke(uid: String, draft: PrescriptionDraft) {
    require(draft.isValid()) { "Invalid prescription draft: $draft" }
    // The repository creates the IDs on the device, so this also works offline
    val prescription =
        draft.toPrescription(medicationRepository.newId(), medicationRepository::newId)
    medicationRepository.addPrescription(uid, prescription)
  }
}
