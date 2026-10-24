// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.domain.medication.PrescriptionDraft
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.scan.RecognizedText
import com.github.se.oncompanion.model.medication.scan.classify
import java.time.LocalDate

/** A detail of a scanned prescription the patient should check before saving it. */
sealed interface ScannedField {
  /** Who prescribed it. */
  data object PrescribedBy : ScannedField

  /** When it was prescribed. */
  data object PrescribedOn : ScannedField

  /** A [detail] of the medication at [position] in the draft. */
  data class OfMedication(val position: Int, val detail: MedicationDetail) : ScannedField
}

/** The details of a scanned medication that can be to check. */
enum class MedicationDetail {
  NAME,
  DOSAGE,
  FREQUENCY,
  DURATION,
}

/**
 * A prescription read from a photo: the draft the form starts from, and the details the patient
 * must check because they may have been read wrong or not found. A detail not in [toCheck] was read
 * clearly; nothing is saved until the patient confirms the draft.
 */
data class ScannedPrescription(val draft: PrescriptionDraft, val toCheck: Set<ScannedField>)

/**
 * Reads the prescription in [text], what ML Kit read on its photo: the prescribing doctor, the date
 * (read day first; [today] if none) and up to [Prescription.MAX_MEDICATIONS] medications, each
 * starting on the prescription's date. Nothing is guessed: a detail that isn't written stays empty
 * and is to check.
 *
 * @return `null` if no medication could be read (a blank, handwritten or blurry photo): there is
 *   nothing to review, and the patient enters the prescription by hand
 */
fun readPrescription(text: RecognizedText, today: LocalDate): ScannedPrescription? {
  val rows = classify(text)
  val medications = medicationsIn(rows).take(Prescription.MAX_MEDICATIONS)
  if (medications.isEmpty()) return null
  val lines = rows.flatMap { it.lines }
  val doctor = prescriberIn(lines)
  val date = dateIn(lines, today)

  val draft =
      PrescriptionDraft(
          prescribedBy = doctor.value,
          prescribedOn = date.value,
          medications =
              medications.map {
                MedicationDraft(
                    name = it.name.value,
                    dosage = it.dosage.value,
                    frequency = it.frequency.value,
                    startDate = date.value,
                    durationDays = it.durationDays.value,
                )
              },
      )
  val toCheck = buildSet {
    if (!doctor.sure) add(ScannedField.PrescribedBy)
    if (!date.sure) add(ScannedField.PrescribedOn)
    medications.forEachIndexed { position, medication ->
      val details =
          mapOf(
              MedicationDetail.NAME to medication.name.sure,
              MedicationDetail.DOSAGE to medication.dosage.sure,
              MedicationDetail.FREQUENCY to medication.frequency.sure,
              MedicationDetail.DURATION to medication.durationDays.sure,
          )
      details.filterValues { !it }.keys.forEach { add(ScannedField.OfMedication(position, it)) }
    }
  }
  return ScannedPrescription(draft, toCheck)
}
