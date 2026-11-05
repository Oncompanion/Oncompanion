// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRegistrationRepository
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Shows in Planning the events a patient confirmed they registered for (US-30), on their day, with
 * their place below the title. Past weeks show the confirmed events that took place then. Whose
 * events they are is decided by the caller ([ObserveCarePlanning]): the signed-in patient, or the
 * patient a caregiver follows.
 */
class EventPlanningSource(
    private val events: EventRepository,
    private val registrations: EventRegistrationRepository,
) : PlanningRepository {

  /** [ownerUid]'s confirmed events starting within [range], as timed entries, updated live. */
  override fun observeItems(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> =
      combine(
          registrations.observeRegisteredEventIds(ownerUid),
          events.observeEvents(range.startInclusive, range.endExclusive),
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
          timing = PlanningTiming.Timed(startDateTime.atZone(range.zoneId).toInstant()),
          title = title,
          subtitle = place.takeIf { it.isNotBlank() },
      )
}
