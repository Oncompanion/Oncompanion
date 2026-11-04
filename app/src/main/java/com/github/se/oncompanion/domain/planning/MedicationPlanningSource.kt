// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Lists recorded active medication days from the signed-in patient's cached prescriptions. */
class MedicationPlanningSource(
    private val medications: MedicationRepository,
    private val auth: AuthRepository,
) : PlanningRepository {
  /** Clears old patient entries before observing another account; read errors reach the caller. */
  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      auth
          .observeCurrentUser()
          .map { it?.uid }
          .distinctUntilChanged()
          .transformLatest { uid ->
            // Publish the clear even when the new patient's first read fails immediately.
            emit(Result.success(emptyList<PlanningItem>()))
            if (uid != null) {
              emitAll(
                  medications
                      .observeMedications(uid)
                      .map { entries -> Result.success(entries.flatMap { it.occurrences(range) }) }
                      .catch { error ->
                        if (error is CancellationException) throw error
                        emit(Result.failure(error))
                      }
              )
            }
          }
          .buffer(0)
          .map { it.getOrThrow() }

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
