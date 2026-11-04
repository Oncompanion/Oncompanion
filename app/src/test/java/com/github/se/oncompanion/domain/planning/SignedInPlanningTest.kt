// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRegistrationRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SignedInPlanning] is the only place Planning follows the signed-in account: these tests check
 * that its appointments, medications and confirmed events always belong to one patient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SignedInPlanningTest {
  private val zone = ZoneId.of("Europe/Zurich")
  private val day = LocalDate.of(2026, 10, 25)
  private val range = PlanningRange(day, day.plusDays(1), zone)

  private val auth = FakeAuthRepository { AuthUser(it) }
  private val appointments = FakeAppointmentRepository()
  private val medications = FakeMedicationRepository()
  private val registrations = FakeEventRegistrationRepository()
  private val event =
      Event(
          id = "yoga",
          title = "Yoga",
          place = "Ligue office",
          startDateTime = day.atTime(18, 0),
          summary = "Summary",
          description = "Description",
      )

  private fun planning(
      events: EventRepository = FakeEventRepository(listOf(event), zone = { zone })
  ) = SignedInPlanning(auth, appointments, medications, events, registrations)

  /** Gives [uid] one appointment, one medication and one confirmed event. */
  private fun seedPatient(uid: String) {
    appointments.seed(
        uid,
        Appointment(uid, "$uid appointment", range.startInclusive, AppointmentType.CONSULTATION),
    )
    medications.seed(
        uid,
        Prescription(
            "p",
            prescribedOn = day,
            medications =
                listOf(Medication("m", "p", "$uid medication", startDate = day, durationDays = 1)),
        ),
    )
    registrations.confirm(uid, event.id)
  }

  /** Collects every list [planning] emits, in the background of the test. */
  private fun TestScope.collect(planning: SignedInPlanning = planning()) =
      mutableListOf<List<PlanningItem>>().also { emissions ->
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          planning.observeItems(range).collect { emissions += it }
        }
        runCurrent()
      }

  /** Collects [planning], keeping its last list in [latest], and returns its failures. */
  private fun TestScope.failuresOf(planning: SignedInPlanning, latest: MutableList<PlanningItem>) =
      mutableListOf<Throwable>().also { failures ->
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          try {
            planning.observeItems(range).collect {
              latest.clear()
              latest += it
            }
          } catch (error: IllegalStateException) {
            failures += error
          }
        }
        runCurrent()
      }

  private fun List<PlanningItem>.titles() = map { it.title }.toSet()

  @Test
  fun signedOut_showsNoEntries_andReadsNothing() = runTest {
    appointments.observeError = IllegalStateException("Must not read appointments")
    medications.readError = IllegalStateException("Must not read medications")
    registrations.observeError = IllegalStateException("Must not read confirmations")

    assertEquals(listOf(emptyList<PlanningItem>()), collect())
  }

  @Test
  fun aSignedInPatient_seesTheirAppointmentsMedicationsAndConfirmedEvents() = runTest {
    seedPatient("anne")
    auth.signInWithGoogle("anne")

    assertEquals(setOf("anne appointment", "anne medication", "Yoga"), collect().last().titles())
  }

  @Test
  fun switchingAccounts_clearsFirst_andNeverMixesTwoPatients() = runTest {
    seedPatient("anne")
    seedPatient("bob")
    auth.signInWithGoogle("anne")
    val emissions = collect()
    emissions.clear()

    auth.signInWithGoogle("bob")
    runCurrent()

    assertTrue(emissions.first().isEmpty())
    // While the sources restart, no list mixes Anne's entries with Bob's
    assertTrue(emissions.all { entries -> entries.none { it.title.startsWith("anne") } })
    assertEquals(setOf("bob appointment", "bob medication", "Yoga"), emissions.last().titles())
  }

  @Test
  fun afterASwitch_thePreviousPatientsChanges_noLongerReachPlanning() = runTest {
    seedPatient("anne")
    auth.signInWithGoogle("anne")
    val emissions = collect()
    auth.signInWithGoogle("bob")
    runCurrent()
    val count = emissions.size

    seedPatient("anne")
    registrations.remove("anne", event.id)
    runCurrent()

    assertEquals(count, emissions.size)
  }

  @Test
  fun signingOut_clearsTheEntries_andAStoppedObservationStaysStopped() = runTest {
    seedPatient("anne")
    auth.signInWithGoogle("anne")
    val emissions = mutableListOf<List<PlanningItem>>()
    val observation =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          planning().observeItems(range).collect { emissions += it }
        }
    runCurrent()

    auth.signOut()
    runCurrent()
    assertTrue(emissions.last().isEmpty())

    observation.cancel()
    auth.signInWithGoogle("anne")
    runCurrent()
    assertTrue(emissions.last().isEmpty())
  }

  @Test
  fun aProfileChangeForTheSameAccount_doesNotRestartTheReads() = runTest {
    auth.signInWithGoogle("anne")
    val emissions = collect()
    // A restarted read would fail
    appointments.observeError = IllegalStateException("Unexpected restart")
    medications.readError = IllegalStateException("Unexpected restart")

    auth.onSignIn = { AuthUser("anne", displayName = "Anne Updated") }
    auth.signInWithGoogle("anne")
    seedPatient("anne")
    runCurrent()

    assertEquals(setOf("anne appointment", "anne medication", "Yoga"), emissions.last().titles())
  }

  @Test
  fun aNewPatientsFailingRead_clearsThePreviousPatient_beforeFailing() = runTest {
    seedPatient("anne")
    auth.signInWithGoogle("anne")
    val latest = mutableListOf<PlanningItem>()
    val failures = failuresOf(planning(), latest)
    assertEquals(3, latest.size)

    appointments.observeError = IllegalStateException("Read failed")
    auth.signInWithGoogle("bob")
    runCurrent()

    assertTrue(latest.isEmpty())
    assertEquals(listOf("Read failed"), failures.map { it.message })
  }

  @Test
  fun aFailingMedicationsRead_failsPlanning() = runTest {
    auth.signInWithGoogle("anne")
    medications.readError = IllegalStateException("Medications read failed")

    val failures = failuresOf(planning(), mutableListOf())

    assertEquals(listOf("Medications read failed"), failures.map { it.message })
  }

  @Test
  fun failingEvents_leaveTheOtherEntries() = runTest {
    seedPatient("anne")
    auth.signInWithGoogle("anne")
    val failingEvents =
        object : EventRepository by FakeEventRepository(emptyList()) {
          override fun observeEvents(from: Instant, until: Instant): Flow<List<Event>> = flow {
            error("Events couldn't be read")
          }
        }

    assertEquals(
        setOf("anne appointment", "anne medication"),
        collect(planning(events = failingEvents)).last().titles(),
    )
  }

  @Test
  fun aCancelledRead_letsTheNextAccountLoad() = runTest {
    auth.signInWithGoogle("anne")
    appointments.observeError = CancellationException("Observation cancelled")
    val emissions = collect()
    assertTrue(emissions.last().isEmpty())

    appointments.observeError = null
    seedPatient("bob")
    auth.signInWithGoogle("bob")
    runCurrent()

    assertEquals(setOf("bob appointment", "bob medication", "Yoga"), emissions.last().titles())
  }
}
