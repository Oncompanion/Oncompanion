package com.github.se.oncompanion.model.medication

import java.time.LocalDate

/**
 * One medication of a [Prescription], stored at `/users/{uid}/medications/{id}`. Everything is
 * entered or confirmed by the user: the app only records it.
 *
 * @property id the document ID
 * @property prescriptionId the [Prescription] this medication belongs to
 * @property name required, see [isValid]
 * @property dosage optional free text (e.g. "1 tablet")
 * @property frequency optional free text (e.g. "Twice a day")
 * @property startDate first day of intake; it can be later than [Prescription.prescribedOn]
 * @property durationDays number of days of intake counting [startDate]; `null` if there is no end
 */
data class Medication(
    val id: String,
    val prescriptionId: String,
    val name: String,
    val dosage: String? = null,
    val frequency: String? = null,
    val startDate: LocalDate,
    val durationDays: Int? = null,
) {
  /** Whether the medication satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      id.isNotBlank() &&
          prescriptionId.isNotBlank() &&
          name.isNotBlank() &&
          name.length <= MAX_TEXT_LENGTH &&
          (dosage == null || dosage.length <= MAX_TEXT_LENGTH) &&
          (frequency == null || frequency.length <= MAX_TEXT_LENGTH) &&
          (durationDays == null || durationDays in 1..MAX_DURATION_DAYS)

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MAX_TEXT_LENGTH = 100
    /** Keep in sync with `firestore.rules`. */
    const val MAX_DURATION_DAYS = 3650
  }
}
