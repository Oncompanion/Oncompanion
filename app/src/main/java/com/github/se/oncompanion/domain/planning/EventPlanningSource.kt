// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.auth.AuthRepository
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
 * Shows in Planning the events the signed-in patient confirmed they registered for (US-30), on
 * their day, with their place below the title. Past weeks show the confirmed events that took place
 * then.
 */
class EventPlanningSource(
    private val events: EventRepository,
    private val registrations: EventRegistrationRepository,
    private val auth: AuthRepository,
) : PlanningRepository {

  /**
   * The confirmed events starting within [range], as timed entries, updated live. Empty when signed
   * out; on an account change, the previous patient's events are cleared before the next patient's
   * are read.
   */
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      observeSignedInPatient(auth) { uid -> confirmedEvents(uid, range) }

  /** [uid]'s confirmed events in [range]. */
  private fun confirmedEvents(uid: String, range: PlanningRange): Flow<List<PlanningItem>> =
      combine(
          registrations.observeRegisteredEventIds(uid),
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
