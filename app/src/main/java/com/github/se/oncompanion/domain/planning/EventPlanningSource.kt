// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Shows the Ligue's upcoming events in Planning, on their day, with their place below the title.
 * Events are the same for every user, so this source doesn't depend on who is signed in.
 *
 * [EventRepository] only gives upcoming events: past weeks show no events, and an event of today
 * leaves the calendar once it has started.
 */
class EventPlanningSource(private val events: EventRepository) : PlanningRepository {

  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      events.getUpcomingEvents().map { upcoming ->
        upcoming.map { it.toPlanningItem(range) }.filter { it.timing in range }
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
