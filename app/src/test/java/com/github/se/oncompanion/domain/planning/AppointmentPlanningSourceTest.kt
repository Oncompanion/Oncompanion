// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.planning.observeItems
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Checks the calendar contract using the shared appointment fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentPlanningSourceTest {
  private val zone = ZoneId.of("Europe/Zurich")
  private val day = LocalDate.of(2026, 10, 25)
  private val range = PlanningRange(day, day.plusDays(1), zone)
  private val appointments = FakeAppointmentRepository()
  private val source = AppointmentPlanningSource(appointments)

  private fun appointment(id: String, time: Instant = range.startInclusive) =
      Appointment(id, "Consultation $id", time, AppointmentType.CONSULTATION, "HUG")

  /** Keeps appointment identity and display fields without changing the repository ordering. */
  @Test
  fun mapsFieldsAndPreservesChronologicalAndEqualTimeOrdering() = runTest {
    val early = appointment("early")
    val a = appointment("a", range.startInclusive.plusSeconds(3600)).copy(location = null)
    val b = appointment("b", a.scheduledAt).copy(location = "  ")
    appointments.seed("patient", b, a, early)
    val emissions = mutableListOf<List<PlanningItem>>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems("patient", range).collect { emissions += it }
    }
    runCurrent()
    assertEquals(
        listOf("early", "a", "b"),
        emissions.last().map { (it.source as PlanningSource.Appointment).appointmentId },
    )
    assertEquals(PlanningTiming.Timed(early.scheduledAt), emissions.last().first().timing)
    assertEquals(early.title, emissions.last().first().title)
    assertEquals("HUG", emissions.last().first().subtitle)
    assertNull(emissions.last()[1].subtitle)
    assertNull(emissions.last()[2].subtitle)
    assertTrue(emissions.last().all { it.frequency == null })
  }

  /** Includes exactly the requested calendar day during short and long daylight-saving days. */
  @Test
  fun filtersLocalDayBoundariesAcrossBothDaylightSavingTransitions() = runTest {
    for (date in listOf(LocalDate.of(2026, 3, 29), day)) {
      val requested = PlanningRange(date, date.plusDays(1), zone)
      val fixtures =
          listOf(
              appointment("before-$date", requested.startInclusive.minusNanos(1)),
              appointment("start-$date", requested.startInclusive),
              appointment("last-$date", requested.endExclusive.minusNanos(1)),
              appointment("end-$date", requested.endExclusive),
          )
      appointments.seed("patient", *fixtures.toTypedArray())
      var latest = emptyList<PlanningItem>()
      val observation =
          backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            source.observeItems("patient", requested).collect { latest = it }
          }
      runCurrent()
      assertEquals(
          listOf("start-$date", "last-$date"),
          latest.map { (it.source as PlanningSource.Appointment).appointmentId },
      )
      assertTrue(latest.all { it.timing.dateIn(zone) == date })
      observation.cancel()
    }
  }

  /** Receives later additions and assigns their date using the caller's display zone. */
  @Test
  fun emptyAgendaReceivesLiveAdditionsAndUsesRequestedDisplayZone() = runTest {
    val requested = PlanningRange(day, day.plusDays(1), ZoneId.of("Pacific/Honolulu"))
    var latest = emptyList<PlanningItem>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems("patient", requested).collect { latest = it }
    }
    runCurrent()
    assertTrue(latest.isEmpty())
    val time = Instant.parse("2026-10-26T09:30:00Z")
    val id = appointments.addAppointment("patient", appointment("ignored", time))
    runCurrent()
    assertEquals(PlanningSource.Appointment(id), latest.single().source)
    assertEquals(day, latest.single().timing.dateIn(requested.zoneId))
  }

  /** Reads only the appointments of the owner it is asked for. */
  @Test
  fun readsOnlyTheGivenOwnersAppointments() = runTest {
    appointments.seed("a", appointment("a"))
    appointments.seed("b", appointment("b"))

    val items = source.observeItems("b", range).first()

    assertEquals(PlanningSource.Appointment("b"), items.single().source)
  }

  /** A read failure reaches the caller instead of showing an empty day. */
  @Test
  fun readFailureReachesTheCaller() = runTest {
    appointments.observeError = IllegalStateException("Read failed")

    val failure = runCatching { source.observeItems("patient", range).first() }.exceptionOrNull()

    assertEquals("Read failed", failure?.message)
  }
}
