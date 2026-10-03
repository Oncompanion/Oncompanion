package com.github.se.oncompanion.model.planning

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Includes [startInclusive] and excludes [endExclusive]. */
data class PlanningRange(val startInclusive: Instant, val endExclusive: Instant) {
  init {
    require(startInclusive < endExclusive) { "Planning range must have a positive duration" }
  }

  operator fun contains(instant: Instant): Boolean = instant in startInclusive..<endExclusive
}

/**
 * Supplies occurrences derived from feature repositories, not a separate Planning store.
 * Implementations emit locally available snapshots without waiting for network access. Failures are
 * reported through the flow.
 */
interface PlanningRepository {
  fun observeItems(range: PlanningRange): Flow<List<PlanningItem>>
}
