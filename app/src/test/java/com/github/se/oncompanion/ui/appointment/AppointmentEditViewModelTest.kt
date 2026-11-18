// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.appointment

import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/**
 * Existing appointments use the creation validation without losing identity, precision or drafts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentEditViewModelTest {
  /** Editing another field must preserve an existing timing, including an explicit opt-out. */
  @Test
  fun unrelatedEditsPreserveEveryReminderChoice() =
      runTest(dispatcher) {
        for (reminder in AppointmentReminder.entries) {
          repository.seed("uid-patient", appointment.copy(reminder = reminder))
          val vm = form()
          advanceUntilIdle()
          vm.onNotesChange("Corrected notes")
          vm.save()
          advanceUntilIdle()
          assertEquals(reminder, repository.appointments("uid-patient").single().reminder)
        }
      }

  /** A reminder-only draft is dirty and survives later repository snapshots and retry. */
  @Test
  fun reminderOnlyEditIsPreservedAndSaved() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        vm.onReminderChange(AppointmentReminder.NONE)
        repository.seed("uid-patient", appointment.copy(title = "Updated elsewhere"))
        advanceUntilIdle()
        assertEquals(AppointmentReminder.NONE, vm.uiState.value.reminder)
        vm.load()
        advanceUntilIdle()
        assertEquals(AppointmentReminder.NONE, vm.uiState.value.reminder)
        vm.save()
        advanceUntilIdle()
        assertEquals(
            AppointmentReminder.NONE,
            repository.appointments("uid-patient").single().reminder,
        )
      }

  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeAppointmentRepository()
  private val auth = FakeAuthRepository()
  private val zone = ZoneId.of("Europe/Zurich")
  private val appointment =
      Appointment(
          "visit",
          "Consultation",
          Instant.parse("2026-10-25T01:30:42.123456789Z"),
          AppointmentType.CONSULTATION,
          "Room 3",
          "Bring documents",
          Instant.parse("2026-10-01T10:00:00Z"),
      )

  private fun form(id: String = appointment.id) =
      AppointmentFormViewModel(LocalDate.of(2026, 10, 20), zone, repository, auth, id)

  /** Runs all observations and mutations on virtual time. */
  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
    runTest(dispatcher) { auth.signInWithGoogle("patient") }
    repository.seed("uid-patient", appointment)
  }

  /** Restores the shared dispatcher. */
  @After
  fun teardown() {
    Dispatchers.resetMain()
  }

  /** Every recorded field and the original repeated-hour offset are prefilled. */
  @Test
  fun prefillsEveryFieldAndPreservesExactInstant() =
      runTest(dispatcher) {
        val vm = form()
        assertEquals(AppointmentFormLoad.LOADING, vm.uiState.value.load)
        vm.onTitleChange("Cannot edit while loading")
        assertEquals("", vm.uiState.value.title)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state.isEditing)
        assertEquals(AppointmentFormLoad.READY, state.load)
        assertEquals("Consultation", state.title)
        assertEquals(LocalDate.of(2026, 10, 25), state.date)
        assertEquals(LocalTime.of(2, 30, 42, 123456789), state.time)
        assertEquals(ZoneOffset.ofHours(1), state.chosenOffset)
        assertEquals("Room 3", state.location)
        assertEquals("Bring documents", state.notes)
        assertEquals(AppointmentType.CONSULTATION, state.type)
        assertEquals(appointment.scheduledAt, state.instant)
        vm.onDateChange(state.date)
        assertEquals(state.chosenOffset, vm.uiState.value.chosenOffset)
        vm.onNotesChange("Corrected notes")
        vm.save()
        vm.save()
        advanceUntilIdle()
        val saved = repository.appointments("uid-patient").single()
        assertEquals(appointment.id, saved.id)
        assertEquals(appointment.createdAt, saved.createdAt)
        assertEquals(appointment.scheduledAt, saved.scheduledAt)
        assertEquals("Corrected notes", saved.notes)
        assertEquals(1, repository.updateRequests.size)
        assertTrue(vm.onCompletionHandled())
        assertFalse(vm.onCompletionHandled())
        vm.save()
        assertEquals(1, repository.updateRequests.size)
      }

  /** An explicit clock edit is honored even when its displayed hour and minute match. */
  @Test
  fun explicitTimeEditAndOffsetAreNotRoundedAway() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        vm.onTimeChange(LocalTime.of(2, 30, 43))
        assertFalse(vm.uiState.value.canSave)
        vm.onOffsetChange(ZoneOffset.ofHours(2))
        assertEquals(Instant.parse("2026-10-25T00:30:43Z"), vm.uiState.value.instant)
        vm.save()
        advanceUntilIdle()
        assertEquals(
            Instant.parse("2026-10-25T00:30:43Z"),
            repository.appointments("uid-patient").single().scheduledAt,
        )
      }

  /** Snapshot refreshes follow an untouched form but cannot overwrite a patient's edits. */
  @Test
  fun liveUpdatesRespectDraftAndRetryRetainsIt() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        repository.seed("uid-patient", appointment.copy(title = "External correction"))
        advanceUntilIdle()
        assertEquals("External correction", vm.uiState.value.title)
        vm.onTitleChange("My correction")
        repository.seed(
            "uid-patient",
            appointment.copy(title = "Other change", notes = "Other notes"),
        )
        advanceUntilIdle()
        assertEquals("My correction", vm.uiState.value.title)
        assertEquals("Bring documents", vm.uiState.value.notes)
        repository.observeError = IOException()
        vm.load()
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.FAILED, vm.uiState.value.load)
        assertFalse(vm.uiState.value.canSave)
        repository.observeError = null
        vm.load()
        advanceUntilIdle()
        assertEquals("My correction", vm.uiState.value.title)
        assertEquals(AppointmentFormLoad.READY, vm.uiState.value.load)
      }

  /** Empty cache is unavailable until a saved appointment arrives, and removal disables Save. */
  @Test
  fun absentAndRemovedAppointmentsCannotBeWritten() =
      runTest(dispatcher) {
        val vm = form("missing")
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.UNAVAILABLE, vm.uiState.value.load)
        vm.save()
        assertTrue(repository.updateRequests.isEmpty())
        repository.seed(
            "uid-patient",
            appointment.copy(id = "missing", location = null, notes = null),
        )
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.location)
        assertEquals("", vm.uiState.value.notes)
        repository.deleteAppointment("uid-patient", "missing")
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.UNAVAILABLE, vm.uiState.value.load)
        vm.save()
        assertTrue(repository.updateRequests.isEmpty())
      }

  /** Invalid edits and spring gaps use the same validation as a new appointment. */
  @Test
  fun validationAndImmediateFailureRetainDraft() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        vm.onTitleChange(" ")
        assertFalse(vm.uiState.value.canSave)
        vm.onTitleChange("Corrected")
        vm.onLocationChange("x".repeat(Appointment.MAX_LOCATION_LENGTH + 1))
        assertFalse(vm.uiState.value.canSave)
        vm.onLocationChange(" ")
        vm.onNotesChange("x".repeat(Appointment.MAX_NOTES_LENGTH + 1))
        assertFalse(vm.uiState.value.canSave)
        vm.onNotesChange(" ")
        vm.onDateChange(LocalDate.of(2026, 3, 29))
        vm.onTimeChange(LocalTime.of(2, 30))
        assertFalse(vm.uiState.value.canSave)
        vm.onTimeChange(LocalTime.NOON)
        repository.writeError = IOException()
        vm.save()
        advanceUntilIdle()
        assertEquals(AppointmentFormError.SAVE_FAILED, vm.uiState.value.error)
        assertEquals("Corrected", vm.uiState.value.title)
        repository.writeError = null
        vm.save()
        advanceUntilIdle()
        val saved = repository.appointments("uid-patient").single()
        assertEquals("Corrected", saved.title)
        assertNull(saved.location)
        assertNull(saved.notes)
      }

  /** An edit the server rejects after Save returned is undone and reported, so the user is told. */
  @Test
  fun editRejectedLaterByTheServerIsUndoneAndReported() =
      runTest(dispatcher) {
        val rejectedWrites = RejectedWrites()
        val appointments = FakeAppointmentRepository(rejectedWrites = rejectedWrites)
        appointments.seed("uid-patient", appointment)
        appointments.serverRejectsWrites = true
        val vm =
            AppointmentFormViewModel(
                LocalDate.of(2026, 10, 20),
                zone,
                appointments,
                auth,
                appointment.id,
            )
        advanceUntilIdle()
        vm.onTitleChange("Rejected title")

        vm.save()
        advanceUntilIdle()

        // Saved without waiting for the server
        assertNotNull(vm.uiState.value.savedAt)
        assertEquals("Rejected title", appointments.appointments("uid-patient").single().title)
        assertEquals(0, rejectedWrites.unseen.value)

        appointments.rejectPendingWrites()

        assertEquals(listOf(appointment), appointments.appointments("uid-patient"))
        assertEquals(1, rejectedWrites.unseen.value)
      }

  /** Switching accounts before observer delivery rejects old drafts and stale completions. */
  @Test
  fun accountChangesCannotSaveOrCompleteOldDraft() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        vm.onTitleChange("Private correction")
        auth.signInWithGoogle("other")
        vm.save()
        assertFalse(vm.onCompletionHandled())
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.title)
        assertTrue(repository.updateRequests.isEmpty())
        assertEquals(AppointmentFormLoad.UNAVAILABLE, vm.uiState.value.load)
        auth.signOut()
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.NOT_SIGNED_IN, vm.uiState.value.load)
      }

  /** A write that finishes after sign-out cannot deliver navigation to the old session. */
  @Test
  fun inFlightWriteCompletionCannotCrossAccounts() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        repository.writeGate = CompletableDeferred()
        vm.onTitleChange("Correction")
        vm.save()
        advanceUntilIdle()
        assertEquals(SavePhase.SAVING, vm.uiState.value.save)
        auth.signOut()
        advanceUntilIdle()
        repository.writeGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.savedAt)
        assertFalse(vm.onCompletionHandled())
        assertEquals(AppointmentFormLoad.NOT_SIGNED_IN, vm.uiState.value.load)
      }

  /** Authentication/read failures and malformed IDs remain recoverable feedback states. */
  @Test
  fun authFailureRetryAndInvalidId() =
      runTest(dispatcher) {
        auth.observeError = IOException()
        val vm = form()
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.NOT_SIGNED_IN, vm.uiState.value.load)
        auth.observeError = null
        vm.load()
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.READY, vm.uiState.value.load)
        val malformed = form("bad/id")
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.FAILED, malformed.uiState.value.load)
      }

  /** A removed appointment cannot report successful completion after an in-flight update. */
  @Test
  fun removalWhileUpdatingStaysUnavailable() =
      runTest(dispatcher) {
        val vm = form()
        advanceUntilIdle()
        repository.writeGate = CompletableDeferred()
        vm.save()
        advanceUntilIdle()
        vm.load()
        assertEquals(SavePhase.SAVING, vm.uiState.value.save)
        val gate = requireNotNull(repository.writeGate)
        repository.writeGate = null
        repository.deleteAppointment("uid-patient", appointment.id)
        advanceUntilIdle()
        assertEquals(AppointmentFormLoad.UNAVAILABLE, vm.uiState.value.load)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.savedAt)
        assertNotEquals(SavePhase.SAVING, vm.uiState.value.save)
        assertFalse(vm.onCompletionHandled())
        assertTrue(repository.appointments("uid-patient").isEmpty())
      }

  // ----- A caregiver the patient lets edit and delete (US-46) -----

  /** The signed-in user, "uid-patient" here, edits Anne's appointment as her caregiver Sam. */
  private fun caregiverForm(appointments: AppointmentRepository = repository) =
      AppointmentFormViewModel(
          LocalDate.of(2026, 10, 20),
          zone,
          appointments,
          auth,
          appointment.id,
          patientUid = "anne",
          profileRepository =
              FakeUserProfileRepository().apply {
                seed(UserProfile("uid-patient", Role.CAREGIVER, firstName = "Sam"))
              },
      )

  @Test
  fun caregiver_editsThePatientsAppointment_signedAsEditor_keepingWhoAddedIt() =
      runTest(dispatcher) {
        repository.seed("anne", appointment.copy(addedBy = "bob", addedByName = "Bob"))
        val vm = caregiverForm()
        advanceUntilIdle()
        assertEquals("Consultation", vm.uiState.value.title)

        vm.onReminderChange(AppointmentReminder.NONE)
        vm.onNotesChange("Fasting")
        vm.save()
        advanceUntilIdle()

        assertEquals(SavePhase.SAVED, vm.uiState.value.save)
        val saved = repository.appointments("anne").single()
        assertEquals("Fasting", saved.notes)
        assertEquals(AppointmentReminder.NONE, saved.reminder)
        assertEquals("uid-patient", saved.editedBy)
        assertEquals("Sam", saved.editedByName)
        assertEquals("bob", saved.addedBy)
        assertEquals("Bob", saved.addedByName)
        assertEquals(appointment, repository.appointments("uid-patient").single())
      }

  @Test
  fun patientEdit_removesTheCaregiversName() =
      runTest(dispatcher) {
        repository.seed("uid-patient", appointment.copy(editedBy = "carol", editedByName = "Carol"))
        val vm = form()
        advanceUntilIdle()

        vm.onNotesChange("Mine")
        vm.save()
        advanceUntilIdle()

        val saved = repository.appointments("uid-patient").single()
        assertEquals("Mine", saved.notes)
        assertNull(saved.editedBy)
        assertNull(saved.editedByName)
      }

  /** Access taken away before an offline edit synchronizes: the server undoes it, and says so. */
  @Test
  fun caregiverEditRejectedByTheServer_isUndoneAndReported() =
      runTest(dispatcher) {
        val rejectedWrites = RejectedWrites()
        val appointments = FakeAppointmentRepository(rejectedWrites = rejectedWrites)
        appointments.seed("anne", appointment)
        appointments.serverRejectsWrites = true
        val vm = caregiverForm(appointments)
        advanceUntilIdle()
        vm.onReminderChange(AppointmentReminder.NONE)
        vm.onNotesChange("Fasting")

        vm.save()
        advanceUntilIdle()
        assertEquals(SavePhase.SAVED, vm.uiState.value.save)
        appointments.rejectPendingWrites()

        assertEquals(listOf(appointment), appointments.appointments("anne"))
        assertEquals(1, rejectedWrites.unseen.value)
      }
}
