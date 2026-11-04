// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryProvider
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.event.FakeEventRegistrationRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Checks what [PlanningRepositoryProvider] exposes by default and that it can be replaced. How
 * Planning follows the account is tested in [SignedInPlanningTest].
 */
class PlanningRepositoryProviderTest {
  private val original = PlanningRepositoryProvider.repository
  private val appAppointmentRepository = AppointmentRepositoryProvider.repository
  private val appAuthRepository = AuthRepositoryProvider.repository
  private val appEventRepository = EventRepositoryProvider.repository
  private val appRegistrationRepository = EventRegistrationRepositoryProvider.repository
  private val appCaregiverPlaceholder = PlanningRepositoryProvider.caregiverPlaceholder
  private val appMedicationRepository = MedicationRepositoryProvider.repository
  private val medications = FakeMedicationRepository()
  private val registrations = FakeEventRegistrationRepository()

  /** Prevents default wiring from reaching Firebase in unit tests. */
  @Before
  fun replacePrivateProviders() {
    MedicationRepositoryProvider.repository = medications
    EventRegistrationRepositoryProvider.repository = registrations
  }

  @After
  fun restoreProviders() {
    PlanningRepositoryProvider.repository = original
    AppointmentRepositoryProvider.repository = appAppointmentRepository
    AuthRepositoryProvider.repository = appAuthRepository
    EventRepositoryProvider.repository = appEventRepository
    EventRegistrationRepositoryProvider.repository = appRegistrationRepository
    PlanningRepositoryProvider.caregiverPlaceholder = appCaregiverPlaceholder
    MedicationRepositoryProvider.repository = appMedicationRepository
  }

  /**
   * Planning shows the signed-in patient's appointments, medications and the events they confirmed,
   * from their providers, sorted by time, even when they were replaced after the planning provider
   * was first used.
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
    // Two events two hours after the appointment: only the confirmed one is shown
    val (confirmed, other) =
        FakeEventRepository.sampleEvents(day).take(2).map {
          it.copy(
              startDateTime =
                  LocalDateTime.ofInstant(range.startInclusive, range.zoneId).plusHours(2)
          )
        }
    EventRepositoryProvider.repository =
        FakeEventRepository(listOf(confirmed, other), zone = { range.zoneId })
    registrations.confirm("patient", confirmed.id)
    medications.seed("patient", prescription(day))

    val items = original.observeItems(range).first { it.size >= 3 }

    assertEquals(
        listOf("Medication", "Saved consultation", confirmed.title),
        items.map { it.title },
    )
  }

  /**
   * Until #240, a caregiver's Planning and Overview show no events: never the events the patient
   * didn't confirm, nor the caregiver's own confirmations as if they were the patient's.
   */
  @Test
  fun caregiverPlaceholder_showsNoEventsByDefault() = runTest {
    val day = LocalDate.of(2026, 10, 25)
    val range = PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich"))
    val auth = FakeAuthRepository { AuthUser(it) }
    auth.signInWithGoogle("caregiver")
    val event = FakeEventRepository.sampleEvents(day.minusDays(1)).first()
    registrations.confirm("caregiver", event.id)
    AuthRepositoryProvider.repository = auth
    EventRepositoryProvider.repository = FakeEventRepository(listOf(event), zone = { range.zoneId })

    val items = appCaregiverPlaceholder.observeItems(range).first()

    assertEquals(emptyList<String>(), items.map { it.title })
  }

  private fun prescription(day: LocalDate, name: String = "Medication") =
      Prescription(
          "p",
          prescribedOn = day,
          medications = listOf(Medication("m", "p", name, startDate = day, durationDays = 1)),
      )

  /** The caregiver placeholder cannot read a caregiver's own prescriptions as patient data. */
  @Test
  fun caregiverPlaceholderNeverReadsMedications() = runTest {
    medications.readError = IllegalStateException("Must not read medications")
    EventRepositoryProvider.repository = FakeEventRepository(emptyList())
    val day = LocalDate.of(2026, 10, 25)
    assertTrue(
        appCaregiverPlaceholder
            .observeItems(PlanningRange(day, day.plusDays(1), ZoneId.of("Europe/Zurich")))
            .first()
            .isEmpty()
    )
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakePlanningRepository()
    PlanningRepositoryProvider.repository = fake
    assertSame(fake, PlanningRepositoryProvider.repository)
  }
}
