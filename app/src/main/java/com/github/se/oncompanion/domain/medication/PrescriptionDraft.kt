package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import java.time.LocalDate

/**
 * A prescription as the user types or confirms it in the prescription form, before it is saved.
 * Text fields are kept as typed; nothing here is stored.
 *
 * @property prescribedBy optional, empty when not filled in
 * @property prescribedOn the date written on the prescription
 * @property medications the medications listed so far
 */
data class PrescriptionDraft(
    val prescribedBy: String = "",
    val prescribedOn: LocalDate,
    val medications: List<MedicationDraft>,
) {
  /**
   * Whether the draft can be saved: the prescription it makes must be valid. The rules live only in
   * [Prescription.isValid].
   */
  fun isValid(): Boolean {
    // Placeholder IDs: a prescription needs IDs to be checked, the real ones are given on saving
    var placeholders = 0
    return toPrescription(PLACEHOLDER_ID) { "$PLACEHOLDER_ID-${placeholders++}" }.isValid()
  }

  /**
   * The prescription this draft makes, with [id]. A medication that already has an ID keeps it, the
   * others get one from [newId]. Spaces around the text are removed, and an optional field left
   * empty is not stored. The result is only valid if the draft [isValid].
   */
  fun toPrescription(id: String, newId: () -> String): Prescription =
      Prescription(
          id = id,
          prescribedBy = prescribedBy.trim().ifEmpty { null },
          prescribedOn = prescribedOn,
          medications = medications.map { it.toMedication(it.id ?: newId(), id) },
      )
}

/**
 * One medication of a [PrescriptionDraft].
 *
 * @property id the ID of a medication that is already saved; `null` for a new one
 * @property name required, see [isValid]
 * @property dosage optional, empty when not filled in
 * @property frequency optional, empty when not filled in
 * @property startDate first day of intake
 * @property durationDays number of days of intake; `null` if there is no end
 */
data class MedicationDraft(
    val id: String? = null,
    val name: String = "",
    val dosage: String = "",
    val frequency: String = "",
    val startDate: LocalDate,
    val durationDays: Int? = null,
) {
  /**
   * Whether the medication can be saved: the medication it makes must be valid. The rules live only
   * in [Medication.isValid].
   */
  fun isValid(): Boolean = toMedication(PLACEHOLDER_ID, PLACEHOLDER_ID).isValid()

  /**
   * The medication this draft makes, with [id], in the prescription [prescriptionId]. Spaces around
   * the text are removed, and an optional field left empty is not stored. The result is only valid
   * if the draft [isValid].
   */
  fun toMedication(id: String, prescriptionId: String): Medication =
      Medication(
          id = id,
          prescriptionId = prescriptionId,
          name = name.trim(),
          dosage = dosage.trim().ifEmpty { null },
          frequency = frequency.trim().ifEmpty { null },
          startDate = startDate,
          durationDays = durationDays,
      )
}

private const val PLACEHOLDER_ID = "draft"
