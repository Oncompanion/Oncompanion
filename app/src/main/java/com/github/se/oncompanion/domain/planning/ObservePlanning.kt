// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * The Planning data: the entries of every feature that shows up in the calendar (appointments,
 * Ligue events...), merged into one list. Each feature stays the owner of its entries and gives
 * them through its own [PlanningRepository]; adding a feature to Planning is one more source here.
 *
 * The list updates whenever one of the sources changes. If a source fails, the whole list fails, so
 * the screen shows its error and retry instead of a calendar that silently misses entries. A source
 * that isn't essential (the Ligue's events) is wrapped in [OptionalPlanningSource], so its failure
 * only hides its own entries.
 */
class ObservePlanning(private val sources: List<PlanningRepository>) : PlanningRepository {

  /** Merges [sources], e.g. `ObservePlanning(appointments, events)`. */
  constructor(vararg sources: PlanningRepository) : this(sources.toList())

  /** The entries of every source within [range], sorted by day, then by time. */
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> {
    // combine() never emits without any flow to combine
    if (sources.isEmpty()) return flowOf(emptyList())
    // Built once per observation, not on every update of a source
    val order = order(range)
    return combine(sources.map { it.observeItems(range) }) { lists ->
      lists.flatMap { it }.filter { it.timing in range }.sortedWith(order)
    }
  }

  /**
   * Day first; within a day, entries without a time (medications) before timed ones, as the agenda
   * shows them; then by time. The key keeps the order stable between entries at the same time.
   */
  private fun order(range: PlanningRange): Comparator<PlanningItem> =
      compareBy<PlanningItem> { it.timing.dateIn(range.zoneId) }
          .thenBy { (it.timing as? PlanningTiming.Timed)?.instant }
          .thenBy { it.key }
}
