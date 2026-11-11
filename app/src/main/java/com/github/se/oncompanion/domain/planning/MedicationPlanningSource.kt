// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lists recorded active medication days from a patient's cached prescriptions. Whose they are is
 * decided by the caller ([ObserveCarePlanning]), which restarts the observation when it changes;
 * read errors reach the caller. A prescription a caregiver added shows who did, and whether the
 * patient confirmed it (US-43); a rejected one isn't listed.
 */
class MedicationPlanningSource(private val medications: MedicationRepository) : PlanningRepository {

  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> =
      medications.observePrescriptions(ownerUid).map { prescriptions ->
        prescriptions
            .filter { it.confirmation != PrescriptionConfirmation.REJECTED }
            .flatMap { prescription -> prescription.medications.map { it to prescription } }
            .sortedWith(compareBy({ it.first.startDate }, { it.first.name }))
            .flatMap { (medication, prescription) -> medication.occurrences(range, prescription) }
      }

  private fun Medication.occurrences(
      range: PlanningRange,
      prescription: Prescription,
  ): List<PlanningItem> {
    val first = maxOf(startDate, range.startDateInclusive)
    // Compare epoch days so a duration near LocalDate.MAX cannot overflow calendar arithmetic.
    val end =
        minOf(
            range.endDateExclusive.toEpochDay(),
            durationDays?.let { startDate.toEpochDay() + it }
                ?: range.endDateExclusive.toEpochDay(),
        )
    return (first.toEpochDay() until end).map { epochDay ->
      PlanningItem(
          source = PlanningSource.Medication(prescriptionId, id),
          timing = PlanningTiming.DateOnly(java.time.LocalDate.ofEpochDay(epochDay)),
          title = name,
          frequency = frequency,
          addedBy = prescription.addedBy,
          addedByName = prescription.addedByName,
          isConfirmed = prescription.isConfirmed,
      )
    }
  }
}
