// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** Checks what [PlanningRepositoryProvider] exposes by default and that it can be replaced. */
class PlanningRepositoryProviderTest {
  private val original = PlanningRepositoryProvider.repository
  private val appAppointmentRepository = AppointmentRepositoryProvider.repository
  private val appAuthRepository = AuthRepositoryProvider.repository
  private val appEventRepository = EventRepositoryProvider.repository
  private val appMedicationRepository = MedicationRepositoryProvider.repository
  private val medications = FakeMedicationRepository()

  /** Prevents default wiring from reaching Firebase in unit tests. */
  @Before
  fun replaceMedicationProvider() {
    MedicationRepositoryProvider.repository = medications
  }

  @After
  fun restoreProviders() {
    PlanningRepositoryProvider.repository = original
    AppointmentRepositoryProvider.repository = appAppointmentRepository
    AuthRepositoryProvider.repository = appAuthRepository
    EventRepositoryProvider.repository = appEventRepository
    MedicationRepositoryProvider.repository = appMedicationRepository
  }

  /**
   * Planning shows the signed-in patient's appointments and the upcoming Ligue events from their
   * providers, sorted by time, even when they were replaced after the planning provider was first
   * used.
   */
  @Test
  fun showsTheAppointmentsAndTheEventsOfTheProvidersByDefault() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
    val auth = FakeAuthRepository { AuthUser(it) }
    auth.signInWithGoogle("patient")
    val appointments = FakeAppointmentRepository()
    appointments.seed(
        "patient",
        Appointment(
            "saved",
            "Saved consultation",
            range.startInclusive,
            AppointmentType.CONSULTATION,
        ),
    )
    AuthRepositoryProvider.repository = auth
    AppointmentRepositoryProvider.repository = appointments
    // An event two hours after the appointment; the fake's "now" is before the day starts
    val event =
        FakeEventRepository.sampleEvents(day)
            .first()
            .copy(
                startDateTime =
                    LocalDateTime.ofInstant(range.startInclusive, range.zoneId).plusHours(2)
            )
    EventRepositoryProvider.repository =
        FakeEventRepository(listOf(event), now = { day.minusDays(1).atStartOfDay() })
    medications.seed("patient", prescription(day))

    val items = original.observeItems("patient", range).first { it.size == 3 }

    assertEquals(listOf("Medication", "Saved consultation", event.title), items.map { it.title })
  }

  /** If the events can't be read, the default provider still shows the appointments. */
  @Test
  fun failingEvents_keepTheAppointmentsByDefault() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
    val auth = FakeAuthRepository { AuthUser(it) }
    auth.signInWithGoogle("patient")
    val appointments = FakeAppointmentRepository()
    appointments.seed(
        "patient",
        Appointment(
            "saved",
            "Saved consultation",
            range.startInclusive,
            AppointmentType.CONSULTATION,
        ),
    )
    AuthRepositoryProvider.repository = auth
    AppointmentRepositoryProvider.repository = appointments
    EventRepositoryProvider.repository =
        object : EventRepository by FakeEventRepository(emptyList()) {
          override fun getUpcomingEvents(): Flow<List<Event>> = flow {
            error("Events couldn't be read")
          }
        }
    medications.seed("patient", prescription(day))

    val items = original.observeItems("patient", range).first { it.size == 2 }

    assertEquals(listOf("Medication", "Saved consultation"), items.map { it.title })
  }

  private fun prescription(day: LocalDate, name: String = "Medication") =
      Prescription(
          "p",
          prescribedOn = day,
          medications = listOf(Medication("m", "p", name, startDate = day, durationDays = 1)),
      )

  /** It reads the planning of the owner it is asked for, and never another account's. */
  @Test
  fun readsOnlyTheGivenOwnersPlanning() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
    val appointments = FakeAppointmentRepository()
    for (uid in listOf("a", "b")) {
      medications.seed(uid, prescription(day, "$uid medication"))
      appointments.seed(
          uid,
          Appointment(uid, "$uid appointment", range.startInclusive, AppointmentType.CONSULTATION),
      )
    }
    AppointmentRepositoryProvider.repository = appointments
    EventRepositoryProvider.repository = FakeEventRepository(emptyList())

    val items = original.observeItems("b", range).first { it.size == 2 }

    assertEquals(setOf("b medication", "b appointment"), items.map { it.title }.toSet())
  }

  /** Medication failures reach the caller instead of silently dropping essential entries. */
  @Test
  fun medicationFailureIsReported() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val auth = FakeAuthRepository { AuthUser(it) }
    auth.signInWithGoogle("patient")
    AuthRepositoryProvider.repository = auth
    AppointmentRepositoryProvider.repository = FakeAppointmentRepository()
    EventRepositoryProvider.repository = FakeEventRepository(emptyList())
    medications.readError = IllegalStateException("Medication read failed")
    val failure = runCatching {
      original
          .observeItems("patient", PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich")))
          .collect {}
    }
        .exceptionOrNull()
    assertEquals("Medication read failed", failure?.message)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakePlanningRepository()
    PlanningRepositoryProvider.repository = fake
    assertSame(fake, PlanningRepositoryProvider.repository)
  }
}
