// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Details follow cached observations and never retain another patient's appointment. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentDetailViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeAppointmentRepository()
  private val auth = FakeAuthRepository()
  private val appointment =
      Appointment(
          "visit",
          "Consultation",
          Instant.parse("2026-10-22T10:00:00Z"),
          AppointmentType.CONSULTATION,
      )

  /** Supplies a deterministic main dispatcher. */
  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
  }

  /** Restores the main dispatcher. */
  @After
  fun teardown() {
    Dispatchers.resetMain()
  }

  /** Missing cache entries can later become available and continue updating live. */
  @Test
  fun unavailableThenLoadedAndUpdated() =
      runTest(dispatcher) {
        auth.signInWithGoogle("patient")
        val vm = AppointmentDetailViewModel("visit", repository, auth)
        assertEquals(AppointmentDetailUiState.Loading, vm.uiState.value)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        repository.seed("uid-patient", appointment)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Loaded(appointment), vm.uiState.value)
        repository.seed("uid-patient", appointment.copy(title = "Changed"))
        advanceUntilIdle()
        assertEquals(
            "Changed",
            (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment.title,
        )
      }

  /** Failures show retry without classifying a repository failure as a connection problem. */
  @Test
  fun readFailureRetriesWithoutDuplicateObservation() =
      runTest(dispatcher) {
        auth.signInWithGoogle("patient")
        repository.observeError = IllegalStateException("denied")
        val vm = AppointmentDetailViewModel("visit", repository, auth)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Error, vm.uiState.value)
        repository.observeError = null
        repository.seed("uid-patient", appointment)
        vm.retry()
        vm.retry()
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Loaded(appointment), vm.uiState.value)
      }

  /** Switching owners and signing out clears the first patient's details. */
  @Test
  fun accountChangesClearPrivateState() =
      runTest(dispatcher) {
        repository.seed("uid-first", appointment)
        auth.signInWithGoogle("first")
        val vm = AppointmentDetailViewModel("visit", repository, auth)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is AppointmentDetailUiState.Loaded)
        auth.signInWithGoogle("second")
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        repository.seed("uid-first", appointment.copy(title = "Private"))
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        repository.seed("uid-second", appointment.copy(title = "Second"))
        advanceUntilIdle()
        assertEquals(
            "Second",
            (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment.title,
        )
        auth.signOut()
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        vm.retry()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
      }

  /** Retry restores account observation after an authentication failure. */
  @Test
  fun authFailureRetryObservesLaterAccountChanges() =
      runTest(dispatcher) {
        auth.signInWithGoogle("first")
        repository.seed("uid-first", appointment)
        auth.observeError = IllegalStateException("auth failed")
        val vm = AppointmentDetailViewModel("visit", repository, auth)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Error, vm.uiState.value)
        auth.observeError = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Loaded(appointment), vm.uiState.value)
        auth.signInWithGoogle("second")
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        repository.seed("uid-second", appointment.copy(title = "Second"))
        advanceUntilIdle()
        assertEquals(
            "Second",
            (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment.title,
        )
      }

  /** Synchronous repository validation failures also become a recoverable screen error. */
  @Test
  fun invalidIdentifierIsAnError() =
      runTest(dispatcher) {
        auth.signInWithGoogle("patient")
        val vm = AppointmentDetailViewModel("", repository, auth)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Error, vm.uiState.value)
      }
}
