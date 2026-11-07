// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/** Calendar range with an inclusive start and exclusive end, in the requested display zone. */
data class PlanningRange(
    val startDateInclusive: LocalDate,
    val endDateExclusive: LocalDate,
    val zoneId: ZoneId,
) {
  init {
    require(startDateInclusive < endDateExclusive) {
      "Planning range must have a positive duration"
    }
  }

  /** Inclusive boundary for future queries against timed sources. */
  val startInclusive: Instant = startDateInclusive.atStartOfDay(zoneId).toInstant()

  /** Exclusive boundary; calendar arithmetic also handles daylight-saving changes. */
  val endExclusive: Instant = endDateExclusive.atStartOfDay(zoneId).toInstant()

  /** Whether a timed source occurrence lies inside this range. */
  operator fun contains(instant: Instant): Boolean =
      instant >= startInclusive && instant < endExclusive

  /** Whether a date-only source occurrence lies inside this range. */
  operator fun contains(date: LocalDate): Boolean =
      date >= startDateInclusive && date < endDateExclusive

  /** Filters either timing representation without inventing a medication time. */
  operator fun contains(timing: PlanningTiming): Boolean =
      when (timing) {
        is PlanningTiming.Timed -> timing.instant in this
        is PlanningTiming.DateOnly -> timing.date in this
      }
}

/**
 * Supplies occurrences derived from feature repositories, not a separate Planning store.
 * Implementations emit locally available snapshots without waiting for network access. Failures are
 * reported through the flow. Date-only medications describe recorded active days, not due doses.
 */
fun interface PlanningRepository {
  /**
   * Observes [ownerUid]'s entries within [range], including its zone for future timed-source
   * adapters. A source that is the same for everyone (the Ligue's events) ignores [ownerUid].
   */
  fun observeItems(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>>

  /**
   * Like the other [observeItems], with only the entries of [sections]: a caregiver gets the
   * sections the patient shares with them (US-20), and the others are not read at all, so the
   * server never refuses a read. A source that belongs to no section gives all its entries.
   */
  fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> = observeItems(ownerUid, range)
}
