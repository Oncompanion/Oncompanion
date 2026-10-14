// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventPlanningSourceTest {

  private val zone = ZoneId.of("Europe/Zurich")
  private val monday = LocalDate.of(2026, 10, 19)
  private val week = PlanningRange(monday, monday.plusWeeks(1), zone)

  private fun event(id: String, start: LocalDateTime, place: String = "Ligue office") =
      Event(
          id = id,
          title = "Event $id",
          place = place,
          startDateTime = start,
          summary = "Summary",
          description = "Description",
      )

  /** A repository that gives [events] as they are, like the Firestore one gives upcoming events. */
  private fun repository(vararg events: Event) =
      FakeEventRepository(events.toList(), now = { LocalDateTime.MIN })

  private suspend fun itemsOf(repository: EventRepository, range: PlanningRange = week) =
      EventPlanningSource(repository).observeItems(range).first()

  @Test
  fun anEvent_becomesATimedEntryOnItsDay() = runTest {
    val start = monday.plusDays(2).atTime(18, 30)

    val item = itemsOf(repository(event("walk", start, place = "Parc des Bastions"))).single()

    assertEquals(
        PlanningItem(
            source = PlanningSource.Event("walk"),
            timing = PlanningTiming.Timed(start.atZone(zone).toInstant()),
            title = "Event walk",
            subtitle = "Parc des Bastions",
        ),
        item,
    )
    assertEquals(monday.plusDays(2), item.timing.dateIn(zone))
  }

  @Test
  fun aBlankPlace_givesNoSubtitle() = runTest {
    assertNull(
        itemsOf(repository(event("yoga", monday.atTime(10, 0), place = "  "))).single().subtitle
    )
  }

  @Test
  fun eventsOutsideTheRange_areLeftOut() = runTest {
    val items =
        itemsOf(
            repository(
                event("sunday-before", monday.minusDays(1).atTime(23, 59)),
                event("monday-midnight", monday.atStartOfDay()),
                event("sunday-last", monday.plusDays(6).atTime(23, 59)),
                event("next-monday", monday.plusWeeks(1).atStartOfDay()),
            )
        )

    assertEquals(
        listOf(PlanningSource.Event("monday-midnight"), PlanningSource.Event("sunday-last")),
        items.map { it.source },
    )
  }

  @Test
  fun aWeekAcrossTheClockChange_keepsItsEventsOnTheirDay() = runTest {
    // Summer time ends on Sunday 25 October 2026 in Zurich
    val clockChangeWeek = PlanningRange(monday, monday.plusWeeks(1), zone)
    val sunday = LocalDate.of(2026, 10, 25)

    val item = itemsOf(repository(event("late", sunday.atTime(23, 30))), clockChangeWeek).single()

    assertEquals(sunday, item.timing.dateIn(zone))
  }

  @Test
  fun noUpcomingEvents_givesAnEmptyList() = runTest { assertTrue(itemsOf(repository()).isEmpty()) }

  @Test
  fun newEvents_updateTheEntries() = runTest {
    val upcoming = MutableStateFlow(listOf(event("first", monday.atTime(9, 0))))
    val repository =
        object : EventRepository {
          override fun getUpcomingEvents(): Flow<List<Event>> = upcoming

          override suspend fun getEvent(id: String): Event? = null
        }
    val emissions = mutableListOf<Int>()
    backgroundScope.launch {
      EventPlanningSource(repository).observeItems(week).collect { emissions += it.size }
    }
    runCurrent()

    upcoming.value = upcoming.value + event("second", monday.atTime(11, 0))
    runCurrent()

    assertEquals(listOf(1, 2), emissions)
  }
}
