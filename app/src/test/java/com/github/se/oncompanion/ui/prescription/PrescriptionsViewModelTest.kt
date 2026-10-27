// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides android.util.Log, used when a read fails
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PrescriptionsViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val uid = "alice"
  private val repository = FakeMedicationRepository()

  /** The time the ViewModel sees; starts on Monday 12 October 2026, 12:00 in UTC. */
  private var current: Clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)

  /** Follows [current], so a test can move the time after creating the ViewModel. */
  private val clock =
      object : Clock() {
        override fun getZone(): ZoneId = current.zone

        override fun withZone(zone: ZoneId): Clock = current.withZone(zone)

        override fun instant(): Instant = current.instant()
      }

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun signedIn(uid: String = this.uid) =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(auth: AuthRepository = signedIn()) =
      PrescriptionsViewModel(auth, repository, clock)

  /** A valid prescription [id] of [prescribedOn] with one medication per name. */
  private fun prescription(id: String, prescribedOn: String, vararg names: String) =
      LocalDate.parse(prescribedOn).let { date ->
        Prescription(
            id = id,
            prescribedOn = date,
            medications =
                names.mapIndexed { index, name ->
                  Medication(id = "$id-$index", prescriptionId = id, name = name, startDate = date)
                },
        )
      }

  private val PrescriptionsViewModel.ids
    get() = uiState.value.prescriptions.map { it.id }

  private val PrescriptionsViewModel.thisMonthIds
    get() = uiState.value.thisMonth.map { it.id }

  private val PrescriptionsViewModel.earlierIds
    get() = uiState.value.earlier.map { it.id }

  @Test
  fun startsLoading_untilThePrescriptionsArrive() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.prescriptions)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf("p1"), viewModel.ids)
      }

  @Test
  fun noPrescriptions_isLoadedWithoutError() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(
            PrescriptionsUiState(today = LocalDate.of(2026, 10, 12), isLoading = false),
            viewModel.uiState.value,
        )
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.thisMonth)
        assertEquals(emptyList<Prescription>(), viewModel.uiState.value.earlier)
      }

  @Test
  fun prescriptions_areTheSignedInUsersOnes_mostRecentlyPrescribedFirst() =
      runTest(dispatcher) {
        val september = prescription("sep", "2026-09-29", "Ondansetron 8 mg", "Dexamethasone 4 mg")
        val october = prescription("oct", "2026-10-06", "Paracetamol 500 mg")
        val august = prescription("aug", "2026-08-12", "Metoclopramide 10 mg")
        listOf(september, october, august).forEach { repository.seed(uid, it) }
        repository.seed("bob", prescription("bob", "2026-10-07", "Ibuprofen 400 mg"))

        val viewModel = viewModel()
        advanceUntilIdle()

        // With their medications, in the order they were saved
        assertEquals(listOf(october, september, august), viewModel.uiState.value.prescriptions)
      }

  @Test
  fun readError_showsTheError() =
      runTest(dispatcher) {
        repository.readError = IllegalStateException("no cache")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.isLoading)
      }

  @Test
  fun signedOut_showsAnError_withoutReading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))

        val viewModel = viewModel(FakeAuthRepository())

        // Known right away: there is nothing to wait for
        assertTrue(viewModel.uiState.value.hasError)
        assertFalse(viewModel.uiState.value.isLoading)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), viewModel.ids)
      }

  @Test
  fun retryAfterError_loadsThePrescriptions() =
      runTest(dispatcher) {
        repository.readError = IllegalStateException("no cache")
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.readError = null
        viewModel.load()

        // Loading again, without the old error
        assertTrue(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.hasError)
        assertEquals(listOf("p1"), viewModel.ids)
      }

  @Test
  fun savedPrescription_appearsWithoutReloading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("old", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.addPrescription(uid, prescription("new", "2026-10-06", "Paracetamol 500 mg"))
        advanceUntilIdle()

        assertEquals(listOf("new", "old"), viewModel.ids)
      }

  @Test
  fun deletedPrescription_disappearsWithoutReloading() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        val viewModel = viewModel()
        advanceUntilIdle()

        repository.deletePrescription(uid, "p1")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.ids)
        assertFalse(viewModel.uiState.value.hasError)
      }

  @Test
  fun reloading_keepsFollowingTheChanges() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.load()
        advanceUntilIdle()

        repository.addPrescription(uid, prescription("p1", "2026-09-29", "Ondansetron 8 mg"))
        advanceUntilIdle()

        assertEquals(listOf("p1"), viewModel.ids)
        assertFalse(viewModel.uiState.value.hasError)
      }

  // ----- This month / Earlier -----

  @Test
  fun thisMonth_startsOnTheFirstDayOfTheCurrentMonth() =
      runTest(dispatcher) {
        listOf(
                prescription("today", "2026-10-12", "A"),
                prescription("first", "2026-10-01", "B"),
                prescription("dayBefore", "2026-09-30", "C"),
                prescription("lastYear", "2025-10-20", "D"),
            )
            .forEach { repository.seed(uid, it) }

        val viewModel = viewModel()
        advanceUntilIdle()

        // Both keep the order of the list, most recently prescribed first
        assertEquals(listOf("today", "first"), viewModel.thisMonthIds)
        assertEquals(listOf("dayBefore", "lastYear"), viewModel.earlierIds)
      }

  @Test
  fun prescriptionDatedLaterThisMonthOrAfter_countsAsThisMonth() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("endOfMonth", "2026-10-31", "A"))
        repository.seed(uid, prescription("nextMonth", "2026-11-02", "B"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("nextMonth", "endOfMonth"), viewModel.thisMonthIds)
        assertEquals(emptyList<String>(), viewModel.earlierIds)
      }

  @Test
  fun nothingThisMonth_leavesThatGroupEmpty() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("old", "2026-08-20", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), viewModel.thisMonthIds)
        assertEquals(listOf("old"), viewModel.earlierIds)
      }

  @Test
  fun nothingEarlier_leavesThatGroupEmpty() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("recent", "2026-10-06", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("recent"), viewModel.thisMonthIds)
        assertEquals(emptyList<String>(), viewModel.earlierIds)
      }

  @Test
  fun today_isCountedInTheClocksTimeZone() =
      runTest(dispatcher) {
        // 23:30 UTC on 30 September is already 1 October in Zurich
        current = Clock.fixed(Instant.parse("2026-09-30T23:30:00Z"), ZoneId.of("Europe/Zurich"))
        repository.seed(uid, prescription("september", "2026-09-29", "A"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(LocalDate.of(2026, 10, 1), viewModel.uiState.value.today)
        assertEquals(listOf("september"), viewModel.earlierIds)
      }

  @Test
  fun followToday_refreshesTheDateRightAway() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        // The date changed while the list was in the background
        current = Clock.fixed(Instant.parse("2026-11-01T00:05:00Z"), ZoneOffset.UTC)
        backgroundScope.launch { viewModel.followToday() }
        runCurrent()

        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.today)
      }

  @Test
  fun followToday_movesLastMonthToEarlierAtMidnight() =
      runTest(dispatcher) {
        repository.seed(uid, prescription("october", "2026-10-06", "A"))
        val clock =
            VirtualClock(testScheduler, Instant.parse("2026-10-31T23:59:00Z"), ZoneOffset.UTC)
        val viewModel = PrescriptionsViewModel(signedIn(), repository, clock)
        advanceUntilIdle()
        backgroundScope.launch { viewModel.followToday() }
        runCurrent()
        assertEquals(listOf("october"), viewModel.thisMonthIds)

        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.today)
        assertEquals(emptyList<String>(), viewModel.thisMonthIds)
        assertEquals(listOf("october"), viewModel.earlierIds)
      }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() =
      runTest(dispatcher) {
        // Like the app's NavHost does: without Firebase nobody is signed in
        assertTrue(PrescriptionsViewModel().uiState.value.hasError)
      }
}
