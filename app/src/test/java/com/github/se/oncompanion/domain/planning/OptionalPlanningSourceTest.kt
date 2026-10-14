// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OptionalPlanningSourceTest {

  private val zone = ZoneId.of("Europe/Zurich")
  private val monday = LocalDate.of(2026, 10, 19)
  private val week = PlanningRange(monday, monday.plusWeeks(1), zone)

  private fun item(id: String, source: PlanningSource, hour: Int) =
      PlanningItem(
          source,
          PlanningTiming.Timed(monday.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()),
          id,
      )

  private val chemo = item("Chemotherapy", PlanningSource.Appointment("chemo"), 9)
  private val yoga = item("Gentle yoga", PlanningSource.Event("yoga"), 14)

  private fun failing() = PlanningRepository { flow { error("Events couldn't be read") } }

  @Test
  fun aWorkingSource_isLeftUnchanged() = runTest {
    val events = FakePlanningRepository().apply { items.value = listOf(yoga) }

    assertEquals(listOf(yoga), OptionalPlanningSource(events).observeItems(week).first())
  }

  @Test
  fun aFailingSource_showsNoEntries_insteadOfFailing() = runTest {
    assertTrue(OptionalPlanningSource(failing()).observeItems(week).first().isEmpty())
  }

  @Test
  fun aSourceFailingLater_dropsOnlyItsOwnEntries() = runTest {
    val events = flow {
      emit(listOf(yoga))
      error("Permission lost")
    }

    assertEquals(
        listOf(listOf(yoga), emptyList()),
        OptionalPlanningSource { events }.observeItems(week).toList(),
    )
  }

  @Test
  fun withObservePlanning_failingEvents_keepTheAppointments() = runTest {
    val appointments = FakePlanningRepository().apply { items.value = listOf(chemo) }
    val planning = ObservePlanning(appointments, OptionalPlanningSource(failing()))

    assertEquals(listOf(chemo), planning.observeItems(week).first())
  }

  @Test
  fun withObservePlanning_appointmentsUpdate_afterTheEventsFailed() = runTest {
    val appointments = FakePlanningRepository()
    val planning = ObservePlanning(appointments, OptionalPlanningSource(failing()))
    val emissions = mutableListOf<List<PlanningItem>>()
    backgroundScope.launch { planning.observeItems(week).collect { emissions += it } }
    runCurrent()

    appointments.items.value = listOf(chemo)
    runCurrent()

    assertEquals(listOf(emptyList(), listOf(chemo)), emissions)
  }
}
