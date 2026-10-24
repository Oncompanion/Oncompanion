// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.scan.ClassifiedLine
import com.github.se.oncompanion.model.medication.scan.LineKind
import com.github.se.oncompanion.model.medication.scan.PrescriptionLexicon
import com.github.se.oncompanion.model.medication.scan.comparable
import java.time.LocalDate

/**
 * A value read on a prescription, and whether it can be trusted as read. A value that isn't [sure]
 * is shown "to check" in the review: a wrong value is only acceptable when it is flagged.
 */
internal data class Reading<T>(val value: T, val sure: Boolean)

/**
 * The doctor who wrote the prescription: their line as read ("Dr J. Roux - Oncologie"). Not sure if
 * none or several were found, if ML Kit was unsure of the line, or if it had to be cut to fit.
 */
internal fun prescriberIn(lines: List<ClassifiedLine>): Reading<String> {
  val doctors = lines.filter { it.kind == LineKind.DOCTOR }
  val doctor = doctors.firstOrNull() ?: return Reading("", sure = false)
  val name = doctor.text.trim()
  val kept = name.take(Prescription.MAX_PRESCRIBED_BY_LENGTH).trim()
  return Reading(kept, sure = doctors.size == 1 && doctor.line.confidence >= SURE && kept == name)
}

/**
 * The date of the prescription, read day first. [today] if none is found. Not sure then, nor if
 * several dates were found, if ML Kit was unsure of its line, or if it is in the future or more
 * than a year old (more likely misread than really so).
 */
internal fun dateIn(lines: List<ClassifiedLine>, today: LocalDate): Reading<LocalDate> {
  val dateLines = lines.filter { it.kind == LineKind.DATE }
  val dates = dateLines.flatMap { PrescriptionLexicon.datesIn(comparable(it.text)) }.distinct()
  val date = dates.firstOrNull() ?: return Reading(today, sure = false)
  val plausible = !date.isAfter(today) && !date.isBefore(today.minusYears(1))
  return Reading(
      date,
      sure = dates.size == 1 && plausible && dateLines.all { it.line.confidence >= SURE },
  )
}

/**
 * How sure ML Kit must be of a line for what it says to be trusted. Printed text was read at 0.83
 * to 0.92 on the test photos, a handwritten line at 0.57.
 */
internal const val SURE = 0.8f
