// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lists recorded active medication days from patient [uid]'s cached prescriptions.
 * [SignedInPlanning] gives it the signed-in patient's uid.
 */
class MedicationPlanningSource(
    private val medications: MedicationRepository,
    private val uid: String,
) : PlanningRepository {
  /**
   * The patient's active medication days within [range], updated live; read errors reach the
   * caller.
   */
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      medications.observeMedications(uid).map { entries ->
        entries.flatMap { it.occurrences(range) }
      }

  private fun Medication.occurrences(range: PlanningRange): List<PlanningItem> {
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
      )
    }
  }
}
