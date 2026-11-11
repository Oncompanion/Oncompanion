// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.Instant
import java.time.LocalDate

/**
 * A prescription and the medications it lists, stored as one document at
 * `/users/{uid}/prescriptions/{id}`.
 *
 * @property id the document ID
 * @property prescribedBy optional free text
 * @property prescribedOn the date written on the prescription
 * @property medications at least one, see [isValid]
 * @property createdAt set by the server when the prescription is saved; `null` before that
 * @property addedBy the uid of the caregiver who added it for the patient (US-43), or `null` when
 *   the patient did
 * @property addedByName that caregiver's first name, shown as "Added by Sam"
 * @property confirmation whether the patient confirmed it; only a caregiver's can wait for it
 */
data class Prescription(
    val id: String,
    val prescribedBy: String? = null,
    val prescribedOn: LocalDate,
    val medications: List<Medication>,
    val createdAt: Instant? = null,
    val addedBy: String? = null,
    val addedByName: String? = null,
    val confirmation: PrescriptionConfirmation = PrescriptionConfirmation.CONFIRMED,
) {
  /** Whether the patient confirmed it: theirs always are, a caregiver's once they confirm it. */
  val isConfirmed: Boolean
    get() = confirmation == PrescriptionConfirmation.CONFIRMED

  /** Whether the patient rejected it (US-43): it then shows nowhere, see [visible]. */
  val isRejected: Boolean
    get() = confirmation == PrescriptionConfirmation.REJECTED

  /** Whether the prescription satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      id.isNotBlank() &&
          (prescribedBy == null || prescribedBy.length <= MAX_PRESCRIBED_BY_LENGTH) &&
          medications.size in 1..MAX_MEDICATIONS &&
          medications.all { it.isValid() && it.prescriptionId == id } &&
          medications.distinctBy { it.id }.size == medications.size &&
          // A caregiver's prescription says who added it; the patient's is confirmed
          (if (addedBy == null) isConfirmed
          else !addedByName.isNullOrBlank() && addedByName.length <= MAX_ADDED_BY_NAME_LENGTH)

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MAX_PRESCRIBED_BY_LENGTH = 100
    const val MAX_MEDICATIONS = 20
    /** Keep in sync with `firestore.rules` (isValidAttribution). */
    const val MAX_ADDED_BY_NAME_LENGTH = 50
  }
}

/**
 * Where a prescription stands with the patient (US-43, decided in #332). A caregiver's prescription
 * waits for the patient, who confirms it after checking each field, or rejects it. Prescriptions
 * saved before this existed, and the patient's own, are confirmed.
 */
enum class PrescriptionConfirmation {
  /** The patient's own, or one they confirmed: it counts like any other. */
  CONFIRMED,
  /** Added by a caregiver, not checked by the patient yet. */
  PENDING,
  /** The patient rejected it. */
  REJECTED,
}

/**
 * The prescriptions that show anywhere: all but those the patient rejected (US-43). Every screen,
 * Planning and the duplicate check read them through it, so they always agree.
 */
fun List<Prescription>.visible(): List<Prescription> = filterNot { it.isRejected }

/**
 * The medications of the prescriptions the patient confirmed, earliest start date first, then by
 * name: what may be scheduled or reminded (US-32, US-34). A prescription a caregiver added counts
 * only once the patient confirmed it (US-43).
 */
fun List<Prescription>.confirmedMedications(): List<Medication> {
  val confirmed = filter { it.isConfirmed }
  return confirmed.flatMap { it.medications }.sortedWith(compareBy({ it.startDate }, { it.name }))
}
