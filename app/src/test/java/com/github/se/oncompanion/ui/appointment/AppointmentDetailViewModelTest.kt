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

  private suspend fun TestScope.loaded(): AppointmentDetailViewModel {
    auth.signInWithGoogle("patient")
    repository.seed("uid-patient", appointment)
    val vm = AppointmentDetailViewModel("visit", repository, auth)
    advanceUntilIdle()
    return vm
  }

  /** Asking and cancelling keep storage; confirmation queues one operation and completes. */
  @Test
  fun confirmationCancelAndDuplicateConfirm() =
      runTest(dispatcher) {
        val vm = loaded()
        vm.confirmDelete()
        vm.requestDelete()
        assertTrue(vm.uiState.value.isDeleteDialogShown)
        assertTrue(repository.deleteRequests.isEmpty())
        vm.cancelDelete()
        assertFalse(vm.uiState.value.isDeleteDialogShown)
        assertEquals(listOf(appointment), repository.appointments("uid-patient"))
        repository.writeGate = CompletableDeferred()
        vm.requestDelete()
        vm.confirmDelete()
        vm.confirmDelete()
        runCurrent()
        assertTrue(vm.uiState.value.delete.isDeleting)
        assertTrue(vm.uiState.value.isDeleteDialogShown)
        vm.cancelDelete()
        vm.retry()
        assertTrue(vm.uiState.value.delete.isDeleting)
        assertEquals(listOf("uid-patient" to "visit"), repository.deleteRequests)
        repository.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.canLeaveAfterDeletion())
        assertFalse(vm.uiState.value.isDeleteDialogShown)
        assertTrue(repository.appointments("uid-patient").isEmpty())
        vm.requestDelete()
        vm.confirmDelete()
        vm.retry()
        assertEquals(1, repository.deleteRequests.size)
      }

  /** An enqueue failure keeps the same detail and dialog, with retry available. */
  @Test
  fun immediateFailureRetainsDetailAndRetries() =
      runTest(dispatcher) {
        val vm = loaded()
        repository.writeError = IllegalStateException("enqueue failed")
        vm.requestDelete()
        vm.confirmDelete()
        advanceUntilIdle()
        assertEquals(appointment, (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment)
        assertTrue(vm.uiState.value.delete.hasError)
        assertTrue(vm.uiState.value.isDeleteDialogShown)
        assertFalse(vm.canLeaveAfterDeletion())
        repository.writeError = null
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(vm.canLeaveAfterDeletion())
      }

  /** Live reads preserve confirmation for the same record and drop it when unavailable. */
  @Test
  fun externalChangesPreserveConfirmationUntilRecordDisappears() =
      runTest(dispatcher) {
        val vm = loaded()
        vm.requestDelete()
        repository.seed("uid-patient", appointment.copy(title = "Updated"))
        advanceUntilIdle()
        assertEquals(
            "Updated",
            (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment.title,
        )
        assertTrue(vm.uiState.value.isDeleteDialogShown)
        repository.deleteAppointment("uid-patient", appointment.id)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
        vm.requestDelete()
        vm.confirmDelete()
        assertEquals(1, repository.deleteRequests.size)
        assertFalse(vm.canLeaveAfterDeletion())
      }

  /** A stale displayed record is never deleted under a newly authenticated owner. */
  @Test
  fun switchingAccountBeforeConfirmDoesNotRedirectDeletion() =
      runTest(dispatcher) {
        val vm = loaded()
        repository.seed("uid-second", appointment.copy(title = "Second"))
        vm.requestDelete()
        auth.signInWithGoogle("second")
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(repository.deleteRequests.isEmpty())
        assertEquals(
            "Second",
            (vm.uiState.value as AppointmentDetailUiState.Loaded).appointment.title,
        )
        assertFalse(vm.uiState.value.isDeleteDialogShown)
        auth.signOut()
        advanceUntilIdle()
        vm.confirmDelete()
        assertEquals(AppointmentDetailUiState.Unavailable, vm.uiState.value)
      }

  private suspend fun TestScope.finishAfterAccountSwitch(failure: Boolean) {
    val vm = loaded()
    repository.writeGate = CompletableDeferred()
    vm.requestDelete()
    vm.confirmDelete()
    runCurrent()
    val second = appointment.copy(title = "Second")
    repository.seed("uid-second", second)
    auth.signInWithGoogle("second")
    runCurrent()
    if (failure) repository.writeError = IllegalStateException("old enqueue failed")
    repository.writeGate!!.complete(Unit)
    advanceUntilIdle()
    assertEquals(AppointmentDetailUiState.Loaded(second), vm.uiState.value)
    assertEquals(listOf(second), repository.appointments("uid-second"))
    assertEquals(listOf("uid-patient" to "visit"), repository.deleteRequests)
    assertFalse(vm.canLeaveAfterDeletion())
  }

  /** An old successful enqueue cannot close or mutate the new owner's detail. */
  @Test
  fun delayedSuccessDoesNotAffectAnotherOwner() =
      runTest(dispatcher) { finishAfterAccountSwitch(failure = false) }

  /** An old failed enqueue cannot put an error dialog over the new owner's detail. */
  @Test
  fun delayedFailureDoesNotAffectAnotherOwner() =
      runTest(dispatcher) { finishAfterAccountSwitch(failure = true) }

  /** Returning to the same uid is still a new session; old failures must remain cancelled. */
  @Test
  fun delayedFailureAfterReturningToSameOwnerIsIgnored() =
      runTest(dispatcher) {
        val vm = loaded()
        repository.writeGate = CompletableDeferred()
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        auth.signInWithGoogle("second")
        runCurrent()
        auth.signInWithGoogle("patient")
        runCurrent()
        repository.writeError = IllegalStateException("stale failure")
        repository.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppointmentDetailUiState.Loaded(appointment), vm.uiState.value)
      }

  /** A destroyed ViewModel cancels queued work without reporting a deletion error. */
  @Test
  fun leavingBeforeEnqueueCancelsWithoutFailure() =
      runTest(dispatcher) {
        val vm = loaded()
        val store = androidx.lifecycle.ViewModelStore().apply { put("detail", vm) }
        repository.writeGate = CompletableDeferred()
        vm.requestDelete()
        vm.confirmDelete()
        runCurrent()
        store.clear()
        repository.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(appointment), repository.appointments("uid-patient"))
        assertFalse(vm.uiState.value.delete.hasError)
        assertFalse(vm.canLeaveAfterDeletion())
      }
}
