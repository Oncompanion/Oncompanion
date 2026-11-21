// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRegistrationRepository
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Shows in Planning the events a patient confirmed they registered for (US-30), on their day, with
 * their place below the title. Past weeks show the confirmed events that took place then. Whose
 * events they are is decided by the caller ([ObserveCarePlanning]): the signed-in patient, or the
 * patient a caregiver follows.
 *
 * An event ends at its end time, or [DEFAULT_PLANNING_DURATION] after it starts when that is
 * unknown. One still going on when the range starts is shown too, if it started at most [LOOKBACK]
 * before: events can only be queried by their start, and the Ligue's events last a few hours. An
 * event over several days is missed by a range that starts more than [LOOKBACK] after it.
 */
class EventPlanningSource(
    private val events: EventRepository,
    private val registrations: EventRegistrationRepository,
) : PlanningRepository {

  /**
   * [ownerUid]'s confirmed events within [range], including those under way when it starts, as
   * timed entries, updated live.
   */
  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> =
      combine(
          registrations.observeRegisteredEventIds(ownerUid),
          events.observeEvents(queryStart(range), queryEnd(range)),
      ) { confirmedIds, eventsInRange ->
        eventsInRange
            .filter { it.id in confirmedIds }
            .map { it.toPlanningItem(range) }
            // The repository's local times may be in another zone than Planning's
            .filter { it.timing in range }
      }

  /** Event times are in the phone's time zone, the one Planning displays. */
  private fun Event.toPlanningItem(range: PlanningRange) =
      PlanningItem(
          source = PlanningSource.Event(id),
          timing =
              timedOccurrence(
                  startDateTime.atZone(range.zoneId).toInstant(),
                  endDateTime?.atZone(range.zoneId)?.toInstant(),
              ),
          title = title,
          subtitle = place.takeIf { it.isNotBlank() },
      )

  /**
   * [LOOKBACK] before the range, so the events under way when it starts are read, but never before
   * the earliest time Firestore can store: in Zurich, 1 January of year 1 starts before it.
   */
  private fun queryStart(range: PlanningRange): Instant =
      maxOf(range.startInclusive - LOOKBACK, EARLIEST_QUERY_START)

  /**
   * The end of the range, but never after the latest time Firestore can store: west of UTC (New
   * York), the range of December 9999 ends after it.
   */
  private fun queryEnd(range: PlanningRange): Instant = minOf(range.endExclusive, LATEST_QUERY_END)

  private companion object {
    /** How long before the range an event under way at its start may have started. */
    val LOOKBACK: Duration = Duration.ofDays(1)

    /** The earliest instant a Firestore timestamp can hold. */
    val EARLIEST_QUERY_START: Instant = Instant.parse("0001-01-01T00:00:00Z")

    /** The latest instant a Firestore timestamp can hold. */
    val LATEST_QUERY_END: Instant = Instant.parse("9999-12-31T23:59:59.999999999Z")
  }
}
