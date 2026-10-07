package com.github.se.oncompanion.model.medication

import java.time.Instant
import java.time.LocalDate

/**
 * A prescription and the medications it lists. What the medications share is stored at
 * `/users/{uid}/prescriptions/{id}`; each of the [medications] is its own document, see
 * [Medication].
 *
 * @property id the document ID
 * @property prescribedBy optional free text
 * @property prescribedOn the date written on the prescription
 * @property medications at least one, see [isValid]
 * @property createdAt set by the server when the prescription is saved; `null` before that
 */
data class Prescription(
    val id: String,
    val prescribedBy: String? = null,
    val prescribedOn: LocalDate,
    val medications: List<Medication>,
    val createdAt: Instant? = null,
) {
  /** Whether the prescription satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      id.isNotBlank() &&
          (prescribedBy == null || prescribedBy.length <= MAX_PRESCRIBED_BY_LENGTH) &&
          medications.size in 1..MAX_MEDICATIONS &&
          medications.all { it.isValid() && it.prescriptionId == id } &&
          medications.distinctBy { it.id }.size == medications.size

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MAX_PRESCRIBED_BY_LENGTH = 100
    const val MAX_MEDICATIONS = 20
  }
}
