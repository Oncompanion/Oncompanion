// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Exercises confirmed creation and exact local-time validation without Firebase. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppointmentFormViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val day = LocalDate.of(2026, 10, 15)
  private val repo = FakeAppointmentRepository()
  private val auth = FakeAuthRepository()
  private lateinit var vm: AppointmentFormViewModel

  /** Supplies deterministic dispatching and an authenticated patient. */
  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
    runTest(dispatcher) { auth.signInWithGoogle("patient") }
    vm = AppointmentFormViewModel(day, ZoneId.of("Europe/Zurich"), repo, auth)
  }

  /** Restores the process dispatcher for other tests. */
  @After
  fun teardown() {
    Dispatchers.resetMain()
  }

  private fun valid() {
    vm.onTitleChange(" Consultation ")
    vm.onTimeChange(LocalTime.of(10, 30))
  }

  /** Required values must be explicit and respect storage bounds. */
  @Test
  fun requiredInputAndLengthLimitsBlockSave() {
    assertEquals(day, vm.uiState.value.date)
    vm.save()
    assertFalse(vm.uiState.value.canSave)
    vm.onTitleChange("   ")
    vm.onTimeChange(LocalTime.NOON)
    assertFalse(vm.uiState.value.canSave)
    valid()
    assertTrue(vm.uiState.value.canSave)
    for ((limit, change) in
        listOf<Pair<Int, (String) -> Unit>>(
            Appointment.MAX_TITLE_LENGTH to vm::onTitleChange,
            Appointment.MAX_LOCATION_LENGTH to vm::onLocationChange,
            Appointment.MAX_NOTES_LENGTH to vm::onNotesChange,
        )) {
      change("a".repeat(limit))
      assertTrue(vm.uiState.value.canSave)
      change("a".repeat(limit + 1))
      assertFalse(vm.uiState.value.canSave)
      change("valid")
    }
  }

  /** Limits apply to stored text while whitespace-only titles remain invalid. */
  @Test
  fun surroundingWhitespaceDoesNotConsumeStoredLimits() =
      runTest(dispatcher) {
        advanceUntilIdle()
        vm.onTitleChange("  " + "t".repeat(Appointment.MAX_TITLE_LENGTH) + "  ")
        vm.onTimeChange(LocalTime.NOON)
        vm.onLocationChange("  " + "l".repeat(Appointment.MAX_LOCATION_LENGTH) + "  ")
        vm.onNotesChange("  " + "n".repeat(Appointment.MAX_NOTES_LENGTH) + "  ")
        assertTrue(vm.uiState.value.canSave)
        vm.save()
        advanceUntilIdle()
        val saved = repo.appointments("uid-patient").single()
        assertEquals(Appointment.MAX_TITLE_LENGTH, saved.title.length)
        assertEquals(Appointment.MAX_LOCATION_LENGTH, saved.location!!.length)
        assertEquals(Appointment.MAX_NOTES_LENGTH, saved.notes!!.length)
      }

  /** A save records normalized text and the exact Zurich instant only once. */
  @Test
  fun saveRecordsAllFieldsAndBlocksRepeatedSubmission() =
      runTest(dispatcher) {
        advanceUntilIdle()
        valid()
        vm.onLocationChange(" HUG ")
        vm.onNotesChange(" Bring documents ")
        vm.onTypeChange(AppointmentType.CONSULTATION)
        vm.save()
        vm.save()
        vm.onTitleChange("Changed during save")
        assertTrue(vm.uiState.value.isSaving)
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        val saved = repo.appointments("uid-patient").single()
        assertEquals("Consultation", saved.title)
        assertEquals("HUG", saved.location)
        assertEquals("Bring documents", saved.notes)
        assertEquals(AppointmentType.CONSULTATION, saved.type)
        assertEquals(Instant.parse("2026-10-15T08:30:00Z"), saved.scheduledAt)
        assertEquals(saved.scheduledAt, vm.uiState.value.savedAt)
      }

  /** Optional whitespace is omitted and immediate failures permit a deliberate retry. */
  @Test
  fun failureRetainsInputAndRetrySavesOptionalBlanksAsNull() =
      runTest(dispatcher) {
        advanceUntilIdle()
        valid()
        vm.onLocationChange("  ")
        vm.onNotesChange("  ")
        repo.writeError = IOException()
        vm.save()
        advanceUntilIdle()
        assertEquals(AppointmentFormError.SAVE_FAILED, vm.uiState.value.error)
        assertEquals(" Consultation ", vm.uiState.value.title)
        assertTrue(vm.uiState.value.canSave)
        vm.clearError()
        assertNull(vm.uiState.value.error)
        repo.writeError = null
        vm.save()
        advanceUntilIdle()
        val saved = repo.appointments("uid-patient").single()
        assertNull(saved.location)
        assertNull(saved.notes)
      }

  /** Sign-out clears private input and cannot write under the previous patient. */
  @Test
  fun accountChangesClearDraftAndSignedOutSaveFails() =
      runTest(dispatcher) {
        advanceUntilIdle()
        valid()
        auth.signOut()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.title)
        valid()
        vm.save()
        advanceUntilIdle()
        assertEquals(AppointmentFormError.NOT_SIGNED_IN, vm.uiState.value.error)
        assertTrue(repo.appointments("uid-patient").isEmpty())
        auth.signInWithGoogle("other")
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.title)
      }

  /** Spring clock gaps cannot be silently moved to another time. */
  @Test
  fun nonexistentTimeCannotSave() {
    valid()
    vm.onDateChange(LocalDate.of(2026, 3, 29))
    vm.onTimeChange(LocalTime.of(2, 30))
    assertFalse(vm.uiState.value.canSave)
    assertNull(vm.uiState.value.instant)
  }

  /** Repeated autumn times require a valid explicit occurrence and resolve differently. */
  @Test
  fun repeatedTimeRequiresChoiceAndResetsWhenDateChanges() {
    valid()
    vm.onDateChange(LocalDate.of(2026, 10, 25))
    vm.onTimeChange(LocalTime.of(2, 30))
    assertFalse(vm.uiState.value.canSave)
    vm.onOffsetChange(ZoneOffset.UTC)
    assertFalse(vm.uiState.value.canSave)
    vm.onOffsetChange(ZoneOffset.ofHours(2))
    assertEquals(Instant.parse("2026-10-25T00:30:00Z"), vm.uiState.value.instant)
    vm.onOffsetChange(ZoneOffset.ofHours(1))
    assertEquals(Instant.parse("2026-10-25T01:30:00Z"), vm.uiState.value.instant)
    vm.onDateChange(day)
    assertNull(vm.uiState.value.chosenOffset)
  }

  /** The injected display zone also supports appointments outside Switzerland. */
  @Test
  fun nonZurichZoneUsesItsOwnOffset() {
    val state =
        AppointmentFormUiState(
            day,
            ZoneId.of("America/New_York"),
            title = "Past visit",
            time = LocalTime.of(10, 30),
        )
    assertEquals(Instant.parse("2026-10-15T14:30:00Z"), state.instant)
  }

  /** Switching accounts before observer delivery cannot save the old patient's draft. */
  @Test
  fun accountSwitchBeforeFlowDeliveryRejectsOldDraft() =
      runTest(dispatcher) {
        advanceUntilIdle()
        valid()
        auth.signInWithGoogle("other")
        vm.save()
        advanceUntilIdle()
        assertTrue(repo.appointments("uid-patient").isEmpty())
        assertTrue(repo.appointments("uid-other").isEmpty())
        assertEquals("", vm.uiState.value.title)
      }

  /** Default parameters use the shared providers while callers can still supply fakes directly. */
  @Test
  fun defaultsReadRepositoryProviders() =
      runTest(dispatcher) {
        val oldAppointments =
            com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider.repository
        val oldAuth = com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository
        try {
          com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider.repository =
              repo
          com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository = auth
          val form = AppointmentFormViewModel(day, ZoneId.of("Europe/Zurich"))
          advanceUntilIdle()
          form.onTitleChange("Provider visit")
          form.onTimeChange(LocalTime.NOON)
          form.save()
          advanceUntilIdle()
          assertEquals("Provider visit", repo.appointments("uid-patient").single().title)
        } finally {
          com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider.repository =
              oldAppointments
          com.github.se.oncompanion.model.auth.AuthRepositoryProvider.repository = oldAuth
        }
      }
}
