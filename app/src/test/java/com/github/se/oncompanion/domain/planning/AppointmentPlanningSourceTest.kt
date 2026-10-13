// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Checks the calendar contract using the shared appointment and authentication fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentPlanningSourceTest {
  private val zone = ZoneId.of("Europe/Zurich")
  private val day = LocalDate.of(2026, 10, 25)
  private val range = PlanningRange(day, day.plusDays(1), zone)
  private val appointments = FakeAppointmentRepository()
  private val auth = FakeAuthRepository { AuthUser(it) }
  private val source = AppointmentPlanningSource(appointments, auth)

  private fun appointment(id: String, time: Instant = range.startInclusive) =
      Appointment(id, "Consultation $id", time, AppointmentType.CONSULTATION, "HUG")

  /** Keeps appointment identity and display fields without changing the repository ordering. */
  @Test
  fun mapsFieldsAndPreservesChronologicalAndEqualTimeOrdering() = runTest {
    auth.signInWithGoogle("patient")
    val early = appointment("early")
    val a = appointment("a", range.startInclusive.plusSeconds(3600)).copy(location = null)
    val b = appointment("b", a.scheduledAt).copy(location = "  ")
    appointments.seed("patient", b, a, early)
    val emissions = mutableListOf<List<PlanningItem>>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems(range).collect { emissions += it }
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
    auth.signInWithGoogle("patient")
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
            source.observeItems(requested).collect { latest = it }
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
    auth.signInWithGoogle("patient")
    val requested = PlanningRange(day, day.plusDays(1), ZoneId.of("Pacific/Honolulu"))
    var latest = emptyList<PlanningItem>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems(requested).collect { latest = it }
    }
    runCurrent()
    assertTrue(latest.isEmpty())
    val time = Instant.parse("2026-10-26T09:30:00Z")
    val id = appointments.addAppointment("patient", appointment("ignored", time))
    runCurrent()
    assertEquals(PlanningSource.Appointment(id), latest.single().source)
    assertEquals(day, latest.single().timing.dateIn(requested.zoneId))
  }

  /** Replaces patient data on account changes and stops delivery after cancellation. */
  @Test
  fun accountSwitchClearsOldEntriesAndStopsOldPatientUpdates() = runTest {
    appointments.seed("a", appointment("a"))
    appointments.seed("b", appointment("b"))
    auth.signInWithGoogle("a")
    val emissions = mutableListOf<List<PlanningItem>>()
    val observation =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          source.observeItems(range).collect { emissions += it }
        }
    runCurrent()
    assertEquals(PlanningSource.Appointment("a"), emissions.last().single().source)
    emissions.clear()
    auth.signInWithGoogle("b")
    runCurrent()
    assertTrue(emissions.first().isEmpty())
    assertEquals(PlanningSource.Appointment("b"), emissions.last().single().source)
    val count = emissions.size
    appointments.seed("a", appointment("old-patient-update"))
    runCurrent()
    assertEquals(count, emissions.size)
    auth.signOut()
    runCurrent()
    assertTrue(emissions.last().isEmpty())
    observation.cancel()
    auth.signInWithGoogle("a")
    runCurrent()
    assertTrue(emissions.last().isEmpty())
  }

  /** Avoids signed-out reads and unnecessary restarts when only profile information changes. */
  @Test
  fun repeatedUidKeepsObservationAndSignedOutStateDoesNotReadAppointments() = runTest {
    appointments.observeError = IllegalStateException("Must not read while signed out")
    var latest = emptyList<PlanningItem>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems(range).collect { latest = it }
    }
    runCurrent()
    assertTrue(latest.isEmpty())
    appointments.observeError = null
    auth.signInWithGoogle("patient")
    runCurrent()
    // A restarted observation would fail; profile changes for the same uid must not restart it.
    appointments.observeError = IllegalStateException("Unexpected restart")
    auth.onSignIn = { AuthUser("patient", displayName = "Updated name") }
    auth.signInWithGoogle("patient")
    runCurrent()
    appointments.seed("patient", appointment("live"))
    runCurrent()
    assertEquals(PlanningSource.Appointment("live"), latest.single().source)
  }

  /** Clears the old patient before reporting a new patient's immediate read failure. */
  @Test
  fun switchingToFailingPatientClearsPreviousAgendaAndPropagatesFailure() = runTest {
    auth.signInWithGoogle("a")
    appointments.seed("a", appointment("a"))
    var latest = emptyList<PlanningItem>()
    var failure: Throwable? = null
    val expected = IllegalStateException("Read failed")
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      try {
        source.observeItems(range).collect { latest = it }
      } catch (error: IllegalStateException) {
        failure = error
      }
    }
    runCurrent()
    assertEquals(1, latest.size)
    appointments.observeError = expected
    auth.signInWithGoogle("b")
    runCurrent()
    assertTrue(latest.isEmpty())
    assertEquals(expected.message, failure?.message)
  }

  /** Preserves upstream cancellation instead of translating it into an ordinary read failure. */
  @Test
  fun cancelledPatientObservationAllowsNextAccountToLoad() = runTest {
    auth.signInWithGoogle("patient")
    appointments.observeError = CancellationException("Observation cancelled")
    var latest = emptyList<PlanningItem>()
    val observation =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          source.observeItems(range).collect { latest = it }
        }
    runCurrent()
    assertTrue(latest.isEmpty())
    appointments.observeError = null
    appointments.seed("other", appointment("other"))
    auth.signInWithGoogle("other")
    runCurrent()
    assertEquals(PlanningSource.Appointment("other"), latest.single().source)
    observation.cancel()
  }
}
