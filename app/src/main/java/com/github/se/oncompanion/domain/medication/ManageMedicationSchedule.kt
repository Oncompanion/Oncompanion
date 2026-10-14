// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import kotlinx.coroutines.flow.first

/**
 * Saves a prescription the user has confirmed, with its medications, and changes or deletes a saved
 * one. Every medication write goes through this use case, so that the reminders can later be kept
 * in sync with the stored schedule in one place. There are no reminders yet: for now it only
 * writes.
 *
 * Unlike the other use cases, it has no single `invoke`: [add], [update] and [delete] are named
 * operations of one class rather than use cases of their own, so that the three writes can't be
 * synced differently.
 */
class ManageMedicationSchedule(
    private val medicationRepository: MedicationRepository = MedicationRepositoryProvider.repository
) {

  /**
   * Saves [draft] as a new prescription of [uid]. Returns once it is saved on the device, also
   * offline.
   *
   * @throws IllegalArgumentException if the draft is not [PrescriptionDraft.isValid], or if one of
   *   its medications already has an ID: it comes from a saved prescription, which must be changed,
   *   not added a second time
   */
  suspend fun add(uid: String, draft: PrescriptionDraft) {
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

  /**
   * Replaces the content of the saved prescription [prescriptionId] of [uid] with [draft], so a
   * wrong prescription can be corrected. A medication of the draft that has an ID stays the same
   * saved medication, one without an ID is added, and a saved one that the draft leaves out is
   * removed. Returns once it is saved on the device, also offline.
   *
   * The prescription is read again first, because it can have been deleted since the form was
   * opened (e.g. from another device) and the repository would then silently save nothing. The read
   * answers from this device's cache, so it only catches a deletion this device already knows
   * about, online or offline. A deletion it doesn't know about yet is refused by the server later,
   * and the update is undone.
   *
   * @return `true` if the prescription was replaced; `false` if it no longer exists, in which case
   *   nothing is written and the caller should tell the user
   * @throws IllegalArgumentException if the draft is not [PrescriptionDraft.isValid]
   */
  suspend fun update(uid: String, prescriptionId: String, draft: PrescriptionDraft): Boolean {
    require(draft.isValid()) { "Invalid prescription draft: $draft" }
    // Without this read, the caller couldn't tell a saved correction from one that was dropped
    if (medicationRepository.observePrescription(uid, prescriptionId).first() == null) return false
    // Only the medications added in the form need an ID, the saved ones keep theirs
    val prescription = draft.toPrescription(prescriptionId, medicationRepository::newId)
    medicationRepository.updatePrescription(uid, prescription)
    return true
  }

  /**
   * Deletes the prescription [prescriptionId] of [uid] with its medications. Returns once it is
   * deleted on the device, also offline. Does nothing if the prescription doesn't exist. The caller
   * asks the user to confirm first: a deletion can't be undone.
   */
  suspend fun delete(uid: String, prescriptionId: String) {
    medicationRepository.deletePrescription(uid, prescriptionId)
  }
}
