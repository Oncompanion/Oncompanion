// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRegistrationRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EventPlanningSource] shows in Planning only the events a patient confirmed (US-30, #139), on
 * their day, past weeks included. Following the signed-in account is [SignedInPlanning]'s job,
 * tested in [SignedInPlanningTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventPlanningSourceTest {

  private val zone = ZoneId.of("Europe/Zurich")
  private val monday = LocalDate.of(2026, 10, 19)
  private val week = PlanningRange(monday, monday.plusWeeks(1), zone)

  private val registrations = FakeEventRegistrationRepository()

  private fun event(id: String, start: LocalDateTime, place: String = "Ligue office") =
      Event(
          id = id,
          title = "Event $id",
          place = place,
          startDateTime = start,
          summary = "Summary",
          description = "Description",
      )

  /** The patient's source over [events], whose local times are in Planning's zone. */
  private fun source(vararg events: Event, repository: EventRepository = fakeEvents(*events)) =
      EventPlanningSource(repository, registrations, "patient")

  private fun fakeEvents(vararg events: Event) =
      FakeEventRepository(events.toList(), zone = { zone })

  /** Confirms [uid]'s registration for each of [eventIds]. */
  private fun confirm(uid: String, vararg eventIds: String) {
    eventIds.forEach { registrations.confirm(uid, it) }
  }

  /** Collects every list the source emits for [range], in the background of the test. */
  private fun TestScope.collect(
      source: EventPlanningSource,
      range: PlanningRange = week,
  ): MutableList<List<PlanningItem>> {
    val emissions = mutableListOf<List<PlanningItem>>()
    backgroundScope.launch { source.observeItems(range).collect { emissions += it } }
    runCurrent()
    return emissions
  }

  /** What the source shows for [range] once everything available has been read. */
  private fun TestScope.latest(source: EventPlanningSource, range: PlanningRange = week) =
      collect(source, range).last()

  @Test
  fun aConfirmedEvent_becomesATimedEntryOnItsDay_withItsPlace() = runTest {
    val start = monday.plusDays(2).atTime(18, 30)
    confirm("patient", "walk")

    val item = latest(source(event("walk", start, place = "Parc des Bastions"))).single()

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
    confirm("patient", "yoga")

    val item = latest(source(event("yoga", monday.atTime(10, 0), place = "  "))).single()

    assertNull(item.subtitle)
  }

  @Test
  fun onlyTheConfirmedEvents_areShown() = runTest {
    confirm("patient", "yoga")

    val items =
        latest(source(event("yoga", monday.atTime(10, 0)), event("walk", monday.atTime(14, 0))))

    assertEquals(listOf(PlanningSource.Event("yoga")), items.map { it.source })
  }

  @Test
  fun confirmedEvents_areShownOnlyWithinTheDisplayedWeek() = runTest {
    // (event, when it starts, whether the week shows it)
    val cases =
        listOf(
            Triple("sunday-before", monday.minusDays(1).atTime(23, 59), false),
            Triple("monday-midnight", monday.atStartOfDay(), true),
            Triple("sunday-last", monday.plusDays(6).atTime(23, 59), true),
            Triple("next-monday", monday.plusWeeks(1).atStartOfDay(), false),
        )
    confirm("patient", *cases.map { it.first }.toTypedArray())
    val events = cases.map { (id, start, _) -> event(id, start) }.toTypedArray()

    val items = latest(source(*events))

    assertEquals(
        cases.filter { it.third }.map { PlanningSource.Event(it.first) },
        items.map { it.source },
    )
  }

  @Test
  fun aPastWeek_showsTheConfirmedEventsThatTookPlaceThen() = runTest {
    // The patient confirmed a workshop that took place three weeks before this week
    val pastMonday = monday.minusWeeks(3)
    confirm("patient", "workshop")

    val items =
        latest(
            source(event("workshop", pastMonday.plusDays(1).atTime(18, 0))),
            PlanningRange(pastMonday, pastMonday.plusWeeks(1), zone),
        )

    assertEquals(listOf(PlanningSource.Event("workshop")), items.map { it.source })
  }

  @Test
  fun aWeekAcrossTheClockChange_keepsItsEventsOnTheirDay() = runTest {
    // Summer time ends on Sunday 25 October 2026 in Zurich
    val sunday = LocalDate.of(2026, 10, 25)
    confirm("patient", "late")

    val item = latest(source(event("late", sunday.atTime(23, 30)))).single()

    assertEquals(sunday, item.timing.dateIn(zone))
  }

  @Test
  fun confirmingAndRemoving_updatesPlanningRightAway() = runTest {
    val emissions = collect(source(event("yoga", monday.atTime(10, 0))))
    assertTrue(emissions.last().isEmpty())

    registrations.confirm("patient", "yoga")
    runCurrent()
    assertEquals(listOf(PlanningSource.Event("yoga")), emissions.last().map { it.source })

    registrations.remove("patient", "yoga")
    runCurrent()
    assertTrue(emissions.last().isEmpty())
  }

  @Test
  fun confirmingTwice_showsTheEventOnce() = runTest {
    confirm("patient", "yoga", "yoga")
    val emissions = collect(source(event("yoga", monday.atTime(10, 0))))

    registrations.confirm("patient", "yoga")
    runCurrent()

    assertEquals(listOf(PlanningSource.Event("yoga")), emissions.last().map { it.source })
  }

  @Test
  fun editedAndDeletedEvents_updateTheirEntry() = runTest {
    // The Ligue edits its events in the console: Firestore sends the new list
    val week = MutableStateFlow(listOf(event("yoga", monday.atTime(10, 0))))
    val liveEvents =
        object : EventRepository by fakeEvents() {
          override fun observeEvents(from: Instant, until: Instant): Flow<List<Event>> =
              week.map { events ->
                events.filter { it.startDateTime.atZone(zone).toInstant() in from..<until }
              }
        }
    confirm("patient", "yoga")
    val emissions = collect(source(repository = liveEvents))

    // Renamed
    week.value = listOf(event("yoga", monday.atTime(10, 0)).copy(title = "Chair yoga"))
    runCurrent()
    assertEquals(listOf("Chair yoga"), emissions.last().map { it.title })

    // Moved to another day of the week
    week.value = listOf(event("yoga", monday.plusDays(3).atTime(10, 0)))
    runCurrent()
    assertEquals(monday.plusDays(3), emissions.last().single().timing.dateIn(zone))

    // Deleted
    week.value = emptyList()
    runCurrent()
    assertTrue(emissions.last().isEmpty())
  }

  @Test
  fun aFailingConfirmationsOrEventsRead_failsTheSource() = runTest {
    confirm("patient", "yoga")
    val yoga = event("yoga", monday.atTime(10, 0))
    val failingEvents =
        object : EventRepository by fakeEvents(yoga) {
          override fun observeEvents(from: Instant, until: Instant): Flow<List<Event>> = flow {
            throw IOException("events")
          }
        }
    // (the read that fails, the source with that read failing)
    val cases: List<Pair<String, () -> EventPlanningSource>> =
        listOf(
            "confirmations" to
                {
                  registrations.observeError = IOException("confirmations")
                  source(yoga)
                },
            "events" to
                {
                  registrations.observeError = null
                  source(repository = failingEvents)
                },
        )

    for ((read, failingSource) in cases) {
      val error = runCatching {
        failingSource().observeItems(week).first { it.isNotEmpty() }
      }
          .exceptionOrNull()

      assertEquals("A failing $read read", read, (error as? IOException)?.message)
    }
  }
}
