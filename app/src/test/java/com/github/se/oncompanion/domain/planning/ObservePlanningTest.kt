// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ObservePlanningTest {

  private val zone = ZoneId.of("Europe/Zurich")
  private val monday = LocalDate.of(2026, 10, 19)
  private val week = PlanningRange(monday, monday.plusWeeks(1), zone)

  private fun appointment(id: String, date: LocalDate, hour: Int) =
      PlanningItem(
          source = PlanningSource.Appointment(id),
          timing =
              PlanningTiming.Timed(date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()),
          title = "Appointment $id",
      )

  private fun event(id: String, date: LocalDate, hour: Int) =
      PlanningItem(
          source = PlanningSource.Event(id),
          timing =
              PlanningTiming.Timed(date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()),
          title = "Event $id",
      )

  private fun medication(id: String, date: LocalDate) =
      PlanningItem(
          source = PlanningSource.Medication("prescription", id),
          timing = PlanningTiming.DateOnly(date),
          title = "Medication $id",
      )

  /** A source that always gives [items], whatever the range. */
  private fun source(vararg items: PlanningItem) = PlanningRepository { flowOf(items.toList()) }

  private val PlanningItem.id: String
    get() =
        when (val s = source) {
          is PlanningSource.Appointment -> s.appointmentId
          is PlanningSource.Event -> s.eventId
          is PlanningSource.Medication -> s.medicationId
        }

  @Test
  fun twoSources_areCombinedInOneListSortedByTime() = runTest {
    val appointments =
        source(appointment("a-wed", monday.plusDays(2), 9), appointment("a-mon", monday, 14))
    val events = source(event("e-mon", monday, 10), event("e-wed", monday.plusDays(2), 18))

    val items = ObservePlanning(appointments, events).observeItems(week).first()

    assertEquals(listOf("e-mon", "a-mon", "a-wed", "e-wed"), items.map { it.id })
  }

  @Test
  fun withinADay_entriesWithoutATimeComeFirst() = runTest {
    val items =
        ObservePlanning(source(appointment("a", monday, 8)), source(medication("m", monday)))
            .observeItems(week)
            .first()

    assertEquals(listOf("m", "a"), items.map { it.id })
  }

  @Test
  fun entriesAtTheSameTime_keepAStableOrder() = runTest {
    val first =
        ObservePlanning(source(event("x", monday, 10)), source(appointment("y", monday, 10)))
    val swapped =
        ObservePlanning(source(appointment("y", monday, 10)), source(event("x", monday, 10)))

    assertEquals(
        first.observeItems(week).first().map { it.key },
        swapped.observeItems(week).first().map { it.key },
    )
  }

  @Test
  fun entriesOutsideTheRange_areLeftOut() = runTest {
    val items =
        ObservePlanning(
                source(
                    appointment("before", monday.minusDays(1), 23),
                    appointment("inside", monday, 0),
                    appointment("after", monday.plusWeeks(1), 0),
                ),
                source(medication("next-week", monday.plusWeeks(1))),
            )
            .observeItems(week)
            .first()

    assertEquals(listOf("inside"), items.map { it.id })
  }

  @Test
  fun noSources_givesAnEmptyList() = runTest {
    assertTrue(ObservePlanning(emptyList()).observeItems(week).first().isEmpty())
  }

  @Test
  fun emptySources_giveAnEmptyList() = runTest {
    assertTrue(ObservePlanning(source(), source()).observeItems(week).first().isEmpty())
  }

  @Test
  fun aSourceChanging_updatesTheList() = runTest {
    val events = MutableStateFlow(listOf(event("e1", monday, 10)))
    val planning =
        ObservePlanning(source(appointment("a", monday, 9)), PlanningRepository { events })
    val emissions = mutableListOf<List<String>>()
    backgroundScope.launch {
      planning.observeItems(week).collect { emissions += it.map { i -> i.id } }
    }
    runCurrent()

    events.value = listOf(event("e1", monday, 10), event("e2", monday, 8))
    runCurrent()

    assertEquals(listOf(listOf("a", "e1"), listOf("e2", "a", "e1")), emissions)
  }

  @Test(expected = IllegalStateException::class)
  fun aFailingSource_failsTheList() = runTest {
    val failing = PlanningRepository { flow { error("Events couldn't be read") } }

    ObservePlanning(source(appointment("a", monday, 9)), failing).observeItems(week).first()
  }
}
