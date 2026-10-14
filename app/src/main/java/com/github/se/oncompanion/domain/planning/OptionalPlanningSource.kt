// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import android.util.Log
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * A Planning [source] that is nice to have but not essential, such as the Ligue's events. If it
 * fails, it shows no entries instead of failing the whole calendar, so the patient still sees their
 * appointments and medications.
 *
 * Essential sources (appointments) are not wrapped: a calendar that silently misses a medical
 * appointment would be worse than an error with Retry. A failed optional source is tried again the
 * next time Planning observes its data (another week, or coming back to the screen).
 */
class OptionalPlanningSource(private val source: PlanningRepository) : PlanningRepository {

  /** The entries of [source] within [range], or none once it fails. */
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      source.observeItems(range).catch { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "An optional planning source failed; its entries are left out", e)
        emit(emptyList())
      }

  private companion object {
    const val TAG = "OptionalPlanningSource"
  }
}
