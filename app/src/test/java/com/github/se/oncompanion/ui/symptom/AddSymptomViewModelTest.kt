// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddSymptomViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val uid = "patient-1"
  private val zurich = ZoneId.of("Europe/Zurich")
  private val today = LocalDate.of(2026, 10, 5)
  /** Monday 5 October 2026 at 14:00 in Zurich. */
  private val now = at(today, 14, 0)

  /** The time the ViewModel sees; a test can move it after creating the ViewModel. */
  private var current: Instant = now

  private val clock =
      object : Clock() {
        override fun getZone(): ZoneId = zurich

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)

        override fun instant(): Instant = current
      }

  /** Uses the same time as the ViewModel, as it rejects symptoms dated after it. */
  private val repository = FakeSymptomRepository(clock = { current })

  private fun at(date: LocalDate, hour: Int, minute: Int): Instant =
      date.atTime(hour, minute).atZone(zurich).toInstant()

  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid) })
    runBlocking { auth.signInWithGoogle("token") }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = AddSymptomViewModel(auth, repository, clock)

  private val AddSymptomViewModel.state
    get() = uiState.value

  /** The only entry saved for [uid]. */
  private fun savedEntry(): SymptomEntry = repository.symptoms(uid).single()

  // ----- Picking symptoms -----

  @Test
  fun newForm_isEmpty_andCantBeSaved() {
    val state = viewModel().state

    assertEquals(today.atTime(14, 0), state.now)
    assertTrue(state.isNow)
    assertEquals(emptyList<SymptomType>(), state.types)
    assertNull(state.intensity)
    assertNull(state.date)
    assertNull(state.time)
    assertEquals("", state.notes)
    assertFalse(state.canSave)
  }

  @Test
  fun pickingASymptom_enablesSave_andPickingItAgainUnpicksIt() {
    val viewModel = viewModel()

    viewModel.toggleType(SymptomType.FATIGUE)
    assertEquals(listOf(SymptomType.FATIGUE), viewModel.state.types)
    assertTrue(viewModel.state.canSave)

    viewModel.toggleType(SymptomType.FATIGUE)
    assertEquals(emptyList<SymptomType>(), viewModel.state.types)
    assertFalse(viewModel.state.canSave)
  }

  @Test
  fun severalSymptoms_keepTheOrderTheyWerePicked() {
    val viewModel = viewModel()

    viewModel.toggleType(SymptomType.PAIN)
    viewModel.toggleType(SymptomType.FATIGUE)
    viewModel.toggleType(SymptomType.NAUSEA)
    viewModel.toggleType(SymptomType.FATIGUE)

    assertEquals(listOf(SymptomType.PAIN, SymptomType.NAUSEA), viewModel.state.types)
  }

  // ----- Intensity -----

  @Test
  fun intensity_isOptional() {
    val viewModel = viewModel()
    viewModel.toggleType(SymptomType.NAUSEA)

    assertNull(viewModel.state.intensity)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun changingTheIntensity_setsIt_evenToTheSameValue_andClearRemovesIt() {
    val viewModel = viewModel()

    viewModel.onIntensityChange(4)
    assertEquals(4, viewModel.state.intensity)
    viewModel.onIntensityChange(8)
    // Dragging the slider reports the same value several times: it stays set
    viewModel.onIntensityChange(8)
    assertEquals(8, viewModel.state.intensity)

    viewModel.clearIntensity()
    assertNull(viewModel.state.intensity)
  }

  @Test
  fun intensityOutsideTheScale_isIgnored() {
    val viewModel = viewModel()
    viewModel.onIntensityChange(5)

    viewModel.onIntensityChange(0)
    viewModel.onIntensityChange(11)

    assertEquals(5, viewModel.state.intensity)
  }

  // ----- Notes -----

  @Test
  fun other_requiresNotes_beforeSaving() {
    val viewModel = viewModel()

    viewModel.toggleType(SymptomType.OTHER)
    assertTrue(viewModel.state.notesRequired)
    assertTrue(viewModel.state.isMissingNotes)
    assertFalse(viewModel.state.canSave)

    viewModel.onNotesChange("   ")
    assertFalse(viewModel.state.canSave)

    viewModel.onNotesChange("Hiccups")
    assertFalse(viewModel.state.isMissingNotes)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun notesWithoutOther_areOptional() {
    val viewModel = viewModel()
    viewModel.toggleType(SymptomType.PAIN)

    assertFalse(viewModel.state.notesRequired)
    assertFalse(viewModel.state.isMissingNotes)
  }

  @Test
  fun notesLongerThanAnEntryCanStore_areCut() {
    val viewModel = viewModel()

    viewModel.onNotesChange("a".repeat(SymptomEntry.MAX_NOTES_LENGTH + 10))

    assertEquals(SymptomEntry.MAX_NOTES_LENGTH, viewModel.state.notes.length)
  }

  // ----- When -----

  @Test
  fun aTimeLaterThanNow_cantBeSaved_untilAnEarlierDayIsChosen() {
    val viewModel = viewModel()
    viewModel.toggleType(SymptomType.FATIGUE)

    viewModel.onTimeChange(LocalTime.of(15, 0))
    assertTrue(viewModel.state.isInFuture)
    assertFalse(viewModel.state.canSave)

    viewModel.onDateChange(today.minusDays(1))
    assertFalse(viewModel.state.isInFuture)
    assertTrue(viewModel.state.canSave)
  }

  @Test
  fun aLaterDay_cantBeSaved() {
    val viewModel = viewModel()
    viewModel.toggleType(SymptomType.FATIGUE)

    viewModel.onDateChange(today.plusDays(1))

    assertTrue(viewModel.state.isInFuture)
    assertFalse(viewModel.state.canSave)
  }

  @Test
  fun pickingOnlyATime_isToday() {
    val viewModel = viewModel()

    viewModel.onTimeChange(LocalTime.of(9, 30))

    assertEquals(today, viewModel.state.date)
    assertFalse(viewModel.state.isNow)
  }

  @Test
  fun refreshNow_updatesTheTime_andSaysWhenTheNextMinuteStarts() {
    val viewModel = viewModel()
    current = now.plusSeconds(80)

    val untilNextMinute = viewModel.refreshNow()

    assertEquals(today.atTime(14, 1, 20), viewModel.state.now)
    assertEquals(Duration.ofSeconds(40), untilNextMinute)
  }

  @Test
  fun aTimeLaterThanNow_canBeSaved_onceThatTimeHasPassed() {
    val viewModel = viewModel()
    viewModel.toggleType(SymptomType.FATIGUE)
    viewModel.onTimeChange(LocalTime.of(14, 5))
    assertFalse(viewModel.state.canSave)

    current = at(today, 14, 6)
    viewModel.refreshNow()

    assertFalse(viewModel.state.isInFuture)
    assertTrue(viewModel.state.canSave)
  }

  // ----- Saving -----

  @Test
  fun save_checksTheTimeAgain_whenSaving() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)
        viewModel.onTimeChange(LocalTime.of(14, 5))

        // The time passed without the screen refreshing it
        current = at(today, 14, 6)
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.isSaved)
        assertEquals(at(today, 14, 5), savedEntry().occurredAt)
      }

  @Test
  fun save_afterMidnight_keepsTheDayTheTimeWasPickedOn() =
      runTest(dispatcher) {
        current = at(today, 23, 59)
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)
        viewModel.onTimeChange(LocalTime.of(23, 58))

        current = at(today.plusDays(1), 0, 1)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(at(today, 23, 58), savedEntry().occurredAt)
      }

  @Test
  fun save_withoutDayOrTime_isTheMomentOfSaving() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)

        current = now.plusSeconds(200)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(now.plusSeconds(200), savedEntry().occurredAt)
      }

  @Test
  fun save_storesWhatWasEntered_andFinishesTheForm() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)
        viewModel.toggleType(SymptomType.HEADACHE)
        viewModel.onIntensityChange(6)
        viewModel.onNotesChange("  After the walk \n")

        viewModel.save()
        assertTrue(viewModel.state.isSaving)
        assertFalse(viewModel.state.canSave)
        advanceUntilIdle()

        assertFalse(viewModel.state.isSaving)
        assertTrue(viewModel.state.isSaved)
        val entry = savedEntry()
        assertEquals(listOf(SymptomType.FATIGUE, SymptomType.HEADACHE), entry.types)
        assertEquals(6, entry.intensity)
        assertEquals("After the walk", entry.notes)
      }

  @Test
  fun save_withoutDayOrTime_isNow_withoutIntensityOrNotes() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.NAUSEA)
        viewModel.onNotesChange("  ")

        viewModel.save()
        advanceUntilIdle()

        val entry = savedEntry()
        assertEquals(now, entry.occurredAt)
        assertNull(entry.intensity)
        assertNull(entry.notes)
      }

  @Test
  fun save_usesTheChosenDayAndTime_inThePhonesTimeZone() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.onDateChange(LocalDate.of(2026, 10, 3))
        viewModel.onTimeChange(LocalTime.of(8, 5))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            LocalDate.of(2026, 10, 3).atTime(8, 5).atZone(zurich).toInstant(),
            savedEntry().occurredAt,
        )
      }

  @Test
  fun save_withOnlyATime_isTodayAtThatTime() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.onTimeChange(LocalTime.of(9, 30))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(today.atTime(9, 30).atZone(zurich).toInstant(), savedEntry().occurredAt)
      }

  @Test
  fun save_withOnlyADay_isThatDayAtTheCurrentTime() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.onDateChange(today.minusDays(2))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            today.minusDays(2).atTime(14, 0).atZone(zurich).toInstant(),
            savedEntry().occurredAt,
        )
      }

  @Test
  fun save_whenNothingIsPicked_doesNothing() =
      runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.state.isSaving)
        assertFalse(viewModel.state.isSaved)
        assertTrue(repository.symptoms(uid).isEmpty())
      }

  @Test
  fun save_whenSignedOut_showsTheError_andSavesNothing() =
      runTest(dispatcher) {
        auth.signOut()
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(AddSymptomError.NOT_SIGNED_IN, viewModel.state.error)
        assertFalse(viewModel.state.isSaved)
        assertTrue(repository.symptoms(uid).isEmpty())

        viewModel.clearError()
        assertNull(viewModel.state.error)
      }

  @Test
  fun save_failure_keepsTheForm_andRetrySaves() =
      runTest(dispatcher) {
        repository.writeError = IOException("disk full")
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(AddSymptomError.SAVE_FAILED, viewModel.state.error)
        assertFalse(viewModel.state.isSaving)
        assertFalse(viewModel.state.isSaved)
        assertEquals(listOf(SymptomType.FATIGUE), viewModel.state.types)

        repository.writeError = null
        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.state.error)
        assertTrue(viewModel.state.isSaved)
        assertEquals(listOf(SymptomType.FATIGUE), savedEntry().types)
      }

  @Test
  fun edits_areIgnored_whileSavingAndOnceSaved() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.FATIGUE)

        viewModel.save()
        viewModel.toggleType(SymptomType.PAIN)
        advanceUntilIdle()
        viewModel.onIntensityChange(3)
        viewModel.onNotesChange("Late")
        viewModel.onDateChange(today.minusDays(1))
        viewModel.onTimeChange(LocalTime.of(8, 0))
        viewModel.save()
        advanceUntilIdle()

        assertEquals(
            AddSymptomUiState(
                now = today.atTime(14, 0),
                types = listOf(SymptomType.FATIGUE),
                isSaved = true,
            ),
            viewModel.state,
        )
        assertEquals(1, repository.symptoms(uid).size)
      }

  @Test
  fun defaultRepositories_areTheOnesOfTheProviders() =
      runTest(dispatcher) {
        val previousAuth = AuthRepositoryProvider.repository
        val previousSymptoms = SymptomRepositoryProvider.repository
        try {
          AuthRepositoryProvider.repository = auth
          SymptomRepositoryProvider.repository = repository

          // Only the clock is passed: the repositories come from the providers
          val viewModel = AddSymptomViewModel(clock = clock)
          viewModel.toggleType(SymptomType.NAUSEA)
          viewModel.save()
          advanceUntilIdle()

          assertEquals(listOf(SymptomType.NAUSEA), savedEntry().types)
        } finally {
          AuthRepositoryProvider.repository = previousAuth
          SymptomRepositoryProvider.repository = previousSymptoms
        }
      }

  // ----- Editing a symptom -----

  /** Logged yesterday at 09:12:37.5, with seconds the form doesn't show. */
  private val logged =
      SymptomEntry(
          id = "s1",
          types = listOf(SymptomType.FATIGUE),
          intensity = 4,
          occurredAt = at(today.minusDays(1), 9, 12).plusMillis(37_500),
          notes = "After lunch",
          createdAt = at(today.minusDays(1), 9, 15),
      )

  /** A ViewModel editing [entry], which is stored for [uid]. */
  private fun editing(entry: SymptomEntry = logged): AddSymptomViewModel {
    repository.seed(uid, entry)
    return AddSymptomViewModel(auth, repository, clock, symptomId = entry.id)
  }

  @Test
  fun editing_loadsTheSymptom_andFillsTheForm_withSaveDisabled() =
      runTest(dispatcher) {
        val viewModel = editing()
        assertTrue(viewModel.state.isEditing)
        assertTrue(viewModel.state.isLoading)
        assertFalse(viewModel.state.isFormShown)
        advanceUntilIdle()

        val state = viewModel.state
        assertFalse(state.isLoading)
        assertTrue(state.isFormShown)
        assertEquals(listOf(SymptomType.FATIGUE), state.types)
        assertEquals(4, state.intensity)
        assertEquals(today.minusDays(1), state.date)
        assertEquals(LocalTime.of(9, 12, 37, 500_000_000), state.time)
        assertEquals("After lunch", state.notes)
        assertFalse(state.isNow)
        assertFalse(state.hasChanges)
        assertFalse(state.canSave)
      }

  @Test
  fun editing_aSymptomThatNoLongerExists_isNotFound() =
      runTest(dispatcher) {
        val viewModel = AddSymptomViewModel(auth, repository, clock, symptomId = "missing")
        advanceUntilIdle()

        assertTrue(viewModel.state.isNotFound)
        assertFalse(viewModel.state.isFormShown)
        assertFalse(viewModel.state.canSave)
      }

  @Test
  fun editing_whenLoadingFails_showsTheError_andRetryLoads() =
      runTest(dispatcher) {
        repository.observeError = IOException("no cache")
        val viewModel = editing()
        advanceUntilIdle()
        assertTrue(viewModel.state.hasLoadError)
        assertFalse(viewModel.state.isFormShown)

        repository.observeError = null
        viewModel.load()
        assertTrue(viewModel.state.isLoading)
        assertFalse(viewModel.state.hasLoadError)
        advanceUntilIdle()

        assertTrue(viewModel.state.isFormShown)
        assertEquals("After lunch", viewModel.state.notes)
      }

  @Test
  fun editing_whenSignedOut_showsTheLoadError() =
      runTest(dispatcher) {
        auth.signOut()
        val viewModel = editing()
        advanceUntilIdle()

        assertTrue(viewModel.state.hasLoadError)
        assertFalse(viewModel.state.isLoading)
      }

  @Test
  fun editing_changesBeforeTheSymptomIsLoaded_areIgnored() =
      runTest(dispatcher) {
        val viewModel = editing()
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.onNotesChange("Typed too early")
        advanceUntilIdle()

        assertEquals(listOf(SymptomType.FATIGUE), viewModel.state.types)
        assertEquals("After lunch", viewModel.state.notes)
      }

  @Test
  fun editing_saveIsEnabledByAChange_andDisabledAgainWhenItIsUndone() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()

        viewModel.toggleType(SymptomType.PAIN)
        assertTrue(viewModel.state.canSave)
        viewModel.toggleType(SymptomType.PAIN)
        assertFalse(viewModel.state.canSave)

        viewModel.onIntensityChange(7)
        assertTrue(viewModel.state.canSave)
        viewModel.onIntensityChange(4)
        assertFalse(viewModel.state.canSave)

        // Spaces around the notes aren't saved, so they aren't a change
        viewModel.onNotesChange("  After lunch ")
        assertFalse(viewModel.state.canSave)
        viewModel.onNotesChange("")
        assertTrue(viewModel.state.canSave)
      }

  @Test
  fun editing_untickingAndReTickingAType_isNotAChange() =
      runTest(dispatcher) {
        val viewModel = editing(logged.copy(types = listOf(SymptomType.FATIGUE, SymptomType.PAIN)))
        advanceUntilIdle()

        viewModel.toggleType(SymptomType.FATIGUE)
        viewModel.toggleType(SymptomType.FATIGUE)

        assertFalse(viewModel.state.hasChanges)
        assertFalse(viewModel.state.canSave)
      }

  @Test
  fun editing_everyField_updatesTheSymptom_keepingItsIdAndWhenItWasLogged() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()
        viewModel.toggleType(SymptomType.FATIGUE)
        viewModel.toggleType(SymptomType.NAUSEA)
        viewModel.toggleType(SymptomType.HEADACHE)
        viewModel.clearIntensity()
        viewModel.onDateChange(today.minusDays(2))
        viewModel.onTimeChange(LocalTime.of(20, 30))
        viewModel.onNotesChange("  Worse at night ")

        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.isSaved)
        assertEquals(
            SymptomEntry(
                id = "s1",
                types = listOf(SymptomType.NAUSEA, SymptomType.HEADACHE),
                intensity = null,
                occurredAt = at(today.minusDays(2), 20, 30),
                notes = "Worse at night",
                createdAt = logged.createdAt,
            ),
            savedEntry(),
        )
      }

  @Test
  fun editing_withoutTouchingTheDayOrTime_keepsTheExactMoment() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()
        viewModel.onIntensityChange(8)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(logged.occurredAt, savedEntry().occurredAt)
        assertEquals(8, savedEntry().intensity)
      }

  @Test
  fun editing_aMomentInTheRepeatedHour_whenTheClocksGoBack_keepsIt() =
      runTest(dispatcher) {
        // 02:30 happens twice in Zurich on 25 October 2026; this is the second one (winter time)
        current = Instant.parse("2026-10-26T12:00:00Z")
        val repeated = logged.copy(occurredAt = Instant.parse("2026-10-25T01:30:00Z"))
        val viewModel = editing(repeated)
        advanceUntilIdle()
        viewModel.onIntensityChange(8)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(repeated.occurredAt, savedEntry().occurredAt)
      }

  @Test
  fun editing_onlyTheDay_keepsTheTime() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()
        viewModel.onDateChange(today.minusDays(3))

        viewModel.save()
        advanceUntilIdle()

        assertEquals(at(today.minusDays(3), 9, 12).plusMillis(37_500), savedEntry().occurredAt)
      }

  @Test
  fun editing_tickingOther_withNotesAlreadyFilled_canBeSaved() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()

        viewModel.toggleType(SymptomType.OTHER)
        assertFalse(viewModel.state.isMissingNotes)
        assertTrue(viewModel.state.canSave)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(listOf(SymptomType.FATIGUE, SymptomType.OTHER), savedEntry().types)
        assertEquals("After lunch", savedEntry().notes)
      }

  @Test
  fun editing_tickingOther_withoutNotes_cantBeSaved() =
      runTest(dispatcher) {
        val viewModel = editing(logged.copy(notes = null))
        advanceUntilIdle()

        viewModel.toggleType(SymptomType.OTHER)

        assertTrue(viewModel.state.isMissingNotes)
        assertFalse(viewModel.state.canSave)
      }

  @Test
  fun editing_clearingTheNotesOfOther_cantBeSaved() =
      runTest(dispatcher) {
        val viewModel = editing(logged.copy(types = listOf(SymptomType.OTHER), notes = "Hiccups"))
        advanceUntilIdle()

        viewModel.onNotesChange(" ")

        assertTrue(viewModel.state.isMissingNotes)
        assertFalse(viewModel.state.canSave)
      }

  @Test
  fun editing_toLaterThanItWasLogged_canBeSaved_butNotToTheFuture() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()

        // Logged yesterday at 09:15, moved to today at 13:00 (now is 14:00)
        viewModel.onDateChange(today)
        viewModel.onTimeChange(LocalTime.of(15, 0))
        assertTrue(viewModel.state.isInFuture)
        assertFalse(viewModel.state.canSave)

        viewModel.onTimeChange(LocalTime.of(13, 0))
        assertFalse(viewModel.state.isInFuture)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(at(today, 13, 0), savedEntry().occurredAt)
      }

  @Test
  fun editing_whenSavingFails_keepsTheChanges_andRetrySaves() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()
        viewModel.onIntensityChange(9)
        repository.writeError = IOException("disk full")

        viewModel.save()
        advanceUntilIdle()
        assertEquals(AddSymptomError.SAVE_FAILED, viewModel.state.error)
        assertEquals(4, savedEntry().intensity)

        repository.writeError = null
        viewModel.save()
        advanceUntilIdle()

        assertTrue(viewModel.state.isSaved)
        assertEquals(9, savedEntry().intensity)
      }

  @Test
  fun loggingANewSymptom_neverLoads() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.load()
        advanceUntilIdle()

        assertFalse(viewModel.state.isEditing)
        assertFalse(viewModel.state.isLoading)
        assertTrue(viewModel.state.isFormShown)
      }

  // ----- When a symptom was felt -----

  private val zonedNow = today.atTime(14, 0).atZone(zurich)

  @Test
  fun occurredAt_withNeitherDayNorTime_isNow() {
    assertEquals(now, occurredAt(null, null, zonedNow))
  }

  @Test
  fun occurredAt_completesWhatWasntChosen_withTodayOrTheCurrentTime() {
    assertEquals(at(today.minusDays(2), 14, 0), occurredAt(today.minusDays(2), null, zonedNow))
    assertEquals(at(today, 9, 30), occurredAt(null, LocalTime.of(9, 30), zonedNow))
    assertEquals(
        at(today.minusDays(1), 8, 5),
        occurredAt(today.minusDays(1), LocalTime.of(8, 5), zonedNow),
    )
  }
}
