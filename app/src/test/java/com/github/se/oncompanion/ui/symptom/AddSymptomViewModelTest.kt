// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.lifecycle.ViewModelStore
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.speech.FakeSpeechRecognizer
import com.github.se.oncompanion.model.speech.OfflineSpeech
import com.github.se.oncompanion.model.speech.SpeechError
import com.github.se.oncompanion.model.speech.SpeechEvent
import com.github.se.oncompanion.model.speech.SpeechLanguage
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

  /** The phone's speech recognizer, which the test speaks through. */
  private val speech = FakeSpeechRecognizer()

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

  private fun viewModel(locale: Locale = Locale.ENGLISH) =
      AddSymptomViewModel(auth, repository, clock, speechRecognizer = speech, locale = locale)

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
  fun aPickedSymptom_startsTicked_andCanBeJoinedOrUnticked() {
    // A check-in tile on the Overview opens the form with its symptom picked
    val viewModel =
        AddSymptomViewModel(
            auth,
            repository,
            clock,
            speechRecognizer = speech,
            picked = SymptomType.FEVER,
        )
    assertEquals(listOf(SymptomType.FEVER), viewModel.state.types)
    assertTrue(viewModel.state.canSave)

    viewModel.toggleType(SymptomType.PAIN)
    viewModel.toggleType(SymptomType.FEVER)
    assertEquals(listOf(SymptomType.PAIN), viewModel.state.types)
    assertTrue(repository.symptoms(uid).isEmpty())
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

        assertEquals(SavePhase.SAVED, viewModel.state.save)
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
        assertEquals(SavePhase.SAVING, viewModel.state.save)
        assertFalse(viewModel.state.canSave)
        advanceUntilIdle()

        assertEquals(SavePhase.SAVED, viewModel.state.save)
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

        assertEquals(SavePhase.EDITING, viewModel.state.save)
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
        assertNotEquals(SavePhase.SAVED, viewModel.state.save)
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
        assertEquals(SavePhase.EDITING, viewModel.state.save)
        assertEquals(listOf(SymptomType.FATIGUE), viewModel.state.types)

        repository.writeError = null
        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.state.error)
        assertEquals(SavePhase.SAVED, viewModel.state.save)
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
                save = SavePhase.SAVED,
                canDictate = true,
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
          SpeechRecognizerProvider.recognizer = speech

          // Only the clock is passed: the repositories come from the providers
          val viewModel = AddSymptomViewModel(clock = clock)
          viewModel.toggleType(SymptomType.NAUSEA)
          viewModel.save()
          advanceUntilIdle()

          assertEquals(listOf(SymptomType.NAUSEA), savedEntry().types)
        } finally {
          AuthRepositoryProvider.repository = previousAuth
          SymptomRepositoryProvider.repository = previousSymptoms
          SpeechRecognizerProvider.reset()
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
    return AddSymptomViewModel(
        auth,
        repository,
        clock,
        symptomId = entry.id,
        speechRecognizer = speech,
    )
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
  fun editing_ignoresAPickedSymptom() =
      runTest(dispatcher) {
        repository.seed(uid, logged)
        val viewModel =
            AddSymptomViewModel(
                auth,
                repository,
                clock,
                symptomId = logged.id,
                speechRecognizer = speech,
                picked = SymptomType.PAIN,
            )
        advanceUntilIdle()

        assertEquals(listOf(SymptomType.FATIGUE), viewModel.state.types)
        assertFalse(viewModel.state.hasChanges)
      }

  @Test
  fun editing_aSymptomThatNoLongerExists_isNotFound() =
      runTest(dispatcher) {
        val viewModel =
            AddSymptomViewModel(
                auth,
                repository,
                clock,
                symptomId = "missing",
                speechRecognizer = speech,
            )
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

        assertEquals(SavePhase.SAVED, viewModel.state.save)
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

        assertEquals(SavePhase.SAVED, viewModel.state.save)
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

  // ----- Caregiver mode (US-22) -----

  /** The form of the signed-in caregiver [uid], logging in "patient"'s journal. */
  private fun caregiverViewModel(profiles: UserProfileRepository = FakeUserProfileRepository()) =
      AddSymptomViewModel(
          auth,
          repository,
          clock,
          patientUid = "patient",
          profileRepository = profiles,
          speechRecognizer = speech,
      )

  @Test
  fun caregiver_logsInThePatientsJournal_signedWithTheirUidAndFirstName() =
      runTest(dispatcher) {
        val profiles =
            FakeUserProfileRepository().apply {
              seed(UserProfile(uid, Role.CAREGIVER, firstName = "Sam"))
            }
        val viewModel = caregiverViewModel(profiles)
        viewModel.toggleType(SymptomType.NAUSEA)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(SavePhase.SAVED, viewModel.state.save)
        assertTrue(repository.symptoms(uid).isEmpty())
        val entry = repository.symptoms("patient").single()
        assertEquals(listOf(SymptomType.NAUSEA), entry.types)
        assertEquals(uid, entry.addedBy)
        assertEquals("Sam", entry.addedByName)
      }

  @Test
  fun caregiver_withoutAProfileName_signsWithTheirGoogleName() =
      runTest(dispatcher) {
        auth = FakeAuthRepository(onSignIn = { AuthUser(uid = uid, displayName = "Sam Martin") })
        auth.signInWithGoogle("token")
        val viewModel = caregiverViewModel()
        viewModel.toggleType(SymptomType.NAUSEA)

        viewModel.save()
        advanceUntilIdle()

        assertEquals("Sam", repository.symptoms("patient").single().addedByName)
      }

  @Test
  fun caregiver_accountChangesWhileTheNameIsRead_writesNothing() =
      runTest(dispatcher) {
        // The profile isn't on the phone yet: reading the name waits
        val nameRead = CompletableDeferred<Unit>()
        val profiles =
            object : UserProfileRepository by FakeUserProfileRepository() {
              override fun observeProfile(uid: String): Flow<UserProfile?> = flow {
                nameRead.await()
                emit(UserProfile(uid, Role.CAREGIVER, firstName = "Sam"))
              }
            }
        val viewModel = caregiverViewModel(profiles)
        viewModel.toggleType(SymptomType.NAUSEA)
        viewModel.save()
        advanceUntilIdle()

        auth.signOut()
        auth.onSignIn = { AuthUser(uid = "someone-else") }
        auth.signInWithGoogle("token")
        nameRead.complete(Unit)
        advanceUntilIdle()

        assertTrue(repository.symptoms("patient").isEmpty())
        assertEquals(SavePhase.EDITING, viewModel.state.save)
        assertEquals(AddSymptomError.NOT_SIGNED_IN, viewModel.state.error)
      }

  @Test
  fun patient_ownSymptom_isNotSigned() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.NAUSEA)

        viewModel.save()
        advanceUntilIdle()

        assertNull(savedEntry().addedBy)
        assertNull(savedEntry().addedByName)
      }

  // ----- Dictating a symptom (US-44) -----

  /** A form listening to the patient, who has allowed the microphone. */
  private fun TestScope.listening(viewModel: AddSymptomViewModel = viewModel()) = viewModel.also {
    it.onSpeak(hasMicrophone = true)
    advanceUntilIdle()
  }

  /** The phone hears [events] in the session being listened to. */
  private fun TestScope.hear(vararg events: SpeechEvent) {
    speech.send(*events)
    advanceUntilIdle()
  }

  /** A form that has heard [transcript], ready to use it. */
  private fun TestScope.heard(transcript: String, viewModel: AddSymptomViewModel = viewModel()) =
      listening(viewModel).also { hear(SpeechEvent.FinalText(transcript)) }

  @Test
  fun dictating_isOfferedOnlyOnPhonesWithASpeechRecognizer() =
      runTest(dispatcher) {
        for (available in listOf(true, false)) {
          speech.available = available
          val viewModel = viewModel()

          assertEquals(available, viewModel.state.canDictate)
          viewModel.onSpeak(hasMicrophone = true)
          advanceUntilIdle()
          assertEquals(available, viewModel.state.voice != null)
          viewModel.cancelVoice()
        }
      }

  @Test
  fun withoutTheMicrophone_theAppExplainsBeforeAndroidAsks() =
      runTest(dispatcher) {
        val allowing = viewModel()
        allowing.onSpeak(hasMicrophone = false)
        advanceUntilIdle()
        assertEquals(VoiceUiState.ExplainingMicrophone, allowing.state.voice)
        assertFalse(speech.isListening)

        // Allowed in Android's dialog: listening starts
        allowing.onMicrophoneAnswer(granted = true)
        advanceUntilIdle()
        assertEquals(VoiceUiState.Listening(), allowing.state.voice)
        assertTrue(speech.isListening)
        allowing.cancelVoice()

        // Refused: the screen explains how to allow it
        val refusing = viewModel()
        refusing.onSpeak(hasMicrophone = false)
        refusing.onMicrophoneAnswer(granted = false)
        advanceUntilIdle()
        assertEquals(VoiceUiState.MicrophoneRefused, refusing.state.voice)
        assertFalse(speech.isListening)
      }

  @Test
  fun androidsAnswer_afterThePatientLeftDictating_isIgnored() =
      runTest(dispatcher) {
        // Cancelled while Android asked, or a new form after the app was restarted meanwhile
        val leftDictating: List<Pair<String, () -> AddSymptomViewModel>> =
            listOf(
                "cancelled" to
                    {
                      viewModel().also {
                        it.onSpeak(hasMicrophone = false)
                        it.cancelVoice()
                      }
                    },
                "restarted" to { viewModel() },
            )
        for ((how, form) in leftDictating) {
          for (granted in listOf(true, false)) {
            val viewModel = form()
            viewModel.onMicrophoneAnswer(granted)
            advanceUntilIdle()

            assertNull("$how, granted=$granted", viewModel.state.voice)
            assertEquals("$how, granted=$granted", emptyList<SpeechLanguage>(), speech.languages)
          }
        }
      }

  @Test
  fun allowedInTheSettings_listensOnlyWhileTheMicrophoneIsShownOff() =
      runTest(dispatcher) {
        // Refused, then allowed in the phone's settings: listens right away
        val refused = viewModel()
        refused.onSpeak(hasMicrophone = false)
        refused.onMicrophoneAnswer(granted = false)
        refused.onMicrophoneAllowedInSettings()
        advanceUntilIdle()
        assertTrue(refused.state.voice is VoiceUiState.Listening)
        assertTrue(speech.isListening)
        refused.cancelVoice()

        // Never asked, or back to the form: nothing starts
        val notRefused: List<Pair<String, () -> AddSymptomViewModel>> =
            listOf(
                "form" to { viewModel() },
                "explaining" to { viewModel().also { it.onSpeak(hasMicrophone = false) } },
                "cancelled" to
                    {
                      viewModel().also {
                        it.onSpeak(hasMicrophone = false)
                        it.onMicrophoneAnswer(granted = false)
                        it.cancelVoice()
                      }
                    },
            )
        for ((how, form) in notRefused) {
          val viewModel = form()
          val before = viewModel.state.voice
          viewModel.onMicrophoneAllowedInSettings()
          advanceUntilIdle()
          assertEquals(how, before, viewModel.state.voice)
          assertFalse(how, speech.isListening)
        }
      }

  @Test
  fun listening_showsTheWordsAsTheyAreSaid_thenWhatWasPickedUp() =
      runTest(dispatcher) {
        val viewModel = listening()
        assertEquals(listOf(SpeechLanguage.ENGLISH), speech.languages)

        // What is picked up shows while the patient speaks
        hear(SpeechEvent.Listening, SpeechEvent.PartialText("I feel sick"))
        val listening = viewModel.state.voice as VoiceUiState.Listening
        assertEquals("I feel sick", listening.text)
        assertEquals(listOf(SymptomType.NAUSEA), listening.draft.types)

        hear(SpeechEvent.FinalText("I feel sick 7/10"))
        val heard = viewModel.state.voice as VoiceUiState.Heard
        assertEquals("I feel sick 7/10", heard.transcript)
        assertEquals(listOf(SymptomType.NAUSEA), heard.draft.types)
        assertEquals(7, heard.draft.intensity)
        // Nothing reaches the form before the patient uses it
        assertEquals(emptyList<SymptomType>(), viewModel.state.types)
      }

  @Test
  fun listening_showsHowLoudThePatientIs_acrossNewWords() =
      runTest(dispatcher) {
        val viewModel = listening()

        hear(SpeechEvent.Level(0.7f))
        assertEquals(0.7f, (viewModel.state.voice as VoiceUiState.Listening).level)
        // New words don't reset it
        hear(SpeechEvent.PartialText("I feel sick"))
        assertEquals(0.7f, (viewModel.state.voice as VoiceUiState.Listening).level)
        hear(SpeechEvent.Level(0.2f))
        assertEquals(0.2f, (viewModel.state.voice as VoiceUiState.Listening).level)

        // Once heard, the level no longer changes anything
        hear(SpeechEvent.FinalText("I feel sick"))
        hear(SpeechEvent.Level(0.9f))
        assertTrue(viewModel.state.voice is VoiceUiState.Heard)
      }

  @Test
  fun done_whileListening_stopsThenFillsTheFormWithTheWholeText() =
      runTest(dispatcher) {
        val viewModel = listening()
        hear(SpeechEvent.PartialText("I feel"))

        viewModel.onDone()
        assertEquals(1, speech.stops)
        assertTrue((viewModel.state.voice as VoiceUiState.Listening).isFinishing)
        // A second tap doesn't stop again, and text still coming keeps Done tapped
        viewModel.onDone()
        hear(SpeechEvent.PartialText("I feel sick"))
        assertEquals(1, speech.stops)
        assertTrue((viewModel.state.voice as VoiceUiState.Listening).isFinishing)

        hear(SpeechEvent.FinalText("I feel sick 7/10"))
        assertNull(viewModel.state.voice)
        assertTrue(viewModel.state.isDrafted)
        assertEquals(listOf(SymptomType.NAUSEA), viewModel.state.types)
        assertEquals(7, viewModel.state.intensity)
      }

  @Test
  fun done_withNothingUnderstood_saysSo() =
      runTest(dispatcher) {
        val viewModel = listening()

        viewModel.onDone()
        hear(SpeechEvent.Failed(SpeechError.NO_SPEECH))

        assertEquals(VoiceUiState.Failed(SpeechError.NO_SPEECH), viewModel.state.voice)
        assertFalse(viewModel.state.isDrafted)
      }

  @Test
  fun cancel_andStartOver_controlTheSession() =
      runTest(dispatcher) {
        val viewModel = listening()
        hear(SpeechEvent.FinalText("I feel sick"))
        assertTrue(viewModel.state.voice is VoiceUiState.Heard)

        // Start over drops what was heard and listens again
        viewModel.restartListening()
        advanceUntilIdle()
        assertEquals(VoiceUiState.Listening(), viewModel.state.voice)
        assertEquals(2, speech.languages.size)
        hear(SpeechEvent.PartialText("my head"))

        // Cancel stops listening and goes back to the empty form
        viewModel.cancelVoice()
        advanceUntilIdle()
        assertNull(viewModel.state.voice)
        assertFalse(speech.isListening)
        assertEquals(emptyList<SymptomType>(), viewModel.state.types)
        assertFalse(viewModel.state.isDrafted)
      }

  @Test
  fun startingOver_whileListening_endsTheFirstSession() =
      runTest(dispatcher) {
        val viewModel = listening()
        hear(SpeechEvent.PartialText("my head"))

        // Even after Done: starting over listens afresh
        viewModel.onDone()
        viewModel.restartListening()
        advanceUntilIdle()
        hear(SpeechEvent.PartialText("I feel"))

        val listening = viewModel.state.voice as VoiceUiState.Listening
        assertEquals("I feel", listening.text)
        assertFalse(listening.isFinishing)
        assertEquals(2, speech.languages.size)
      }

  @Test
  fun aFailedListening_saysWhy_andARefusedMicrophoneSaysHowToAllowIt() =
      runTest(dispatcher) {
        val cases =
            SpeechError.entries.associateWith {
              if (it == SpeechError.NO_PERMISSION) VoiceUiState.MicrophoneRefused
              else VoiceUiState.Failed(it)
            }
        for ((error, expected) in cases) {
          val viewModel = listening()
          hear(SpeechEvent.Failed(error))
          assertEquals(error.name, expected, viewModel.state.voice)
        }
      }

  /** What a form shows after using [transcript], from a form that [before] filled. */
  private data class DraftCase(
      val transcript: String,
      val before: (AddSymptomViewModel) -> Unit = {},
      val types: List<SymptomType>,
      val intensity: Int? = null,
      val date: LocalDate? = null,
      val time: LocalTime? = null,
      val notes: String = transcript,
  )

  @Test
  fun usingWhatWasHeard_fillsOnlyTheFieldsStillEmpty() =
      runTest(dispatcher) {
        val yesterday = today.minusDays(1)
        val cases =
            listOf(
                // Everything understood
                DraftCase(
                    "headache 7 out of 10 yesterday at 9 am",
                    types = listOf(SymptomType.HEADACHE),
                    intensity = 7,
                    date = yesterday,
                    time = LocalTime.of(9, 0),
                ),
                // Only some fields understood: the rest stays empty
                DraftCase("I feel sick", types = listOf(SymptomType.NAUSEA)),
                // A time without a day is today, as when it is picked in the form
                DraftCase(
                    "headache at 9 am",
                    types = listOf(SymptomType.HEADACHE),
                    date = today,
                    time = LocalTime.of(9, 0),
                ),
                // Nothing understood: only the notes keep what was said
                DraftCase("hello there", types = emptyList()),
                // What the patient entered is kept; new symptoms come after theirs
                DraftCase(
                    "nausea and headache 7 out of 10 yesterday at 9 am",
                    before = {
                      it.toggleType(SymptomType.HEADACHE)
                      it.onIntensityChange(3)
                      it.onTimeChange(LocalTime.of(11, 0))
                      it.onNotesChange("After lunch  ")
                    },
                    types = listOf(SymptomType.HEADACHE, SymptomType.NAUSEA),
                    intensity = 3,
                    date = today,
                    time = LocalTime.of(11, 0),
                    notes = "After lunch\nnausea and headache 7 out of 10 yesterday at 9 am",
                ),
            )
        for (case in cases) {
          val viewModel = viewModel().also(case.before)
          heard(case.transcript, viewModel)

          viewModel.onDone()
          val state = viewModel.state
          assertNull(case.transcript, state.voice)
          assertTrue(case.transcript, state.isDrafted)
          assertEquals(case.transcript, case.types, state.types)
          assertEquals(case.transcript, case.intensity, state.intensity)
          assertEquals(case.transcript, case.date, state.date)
          assertEquals(case.transcript, case.time, state.time)
          assertEquals(case.transcript, case.notes, state.notes)
        }
      }

  @Test
  fun dictatingWhileEditing_keepsWhatWasSaved_andAddsWhatWasSaid() =
      runTest(dispatcher) {
        val viewModel = editing()
        advanceUntilIdle()
        val said = "nausea 7 out of 10 today at 3 pm"
        heard(said, viewModel)

        viewModel.onDone()
        // The saved symptom, intensity, day and time stay; the new symptom and the words are added
        val state = viewModel.state
        assertTrue(state.isDrafted)
        assertEquals(listOf(SymptomType.FATIGUE, SymptomType.NAUSEA), state.types)
        assertEquals(4, state.intensity)
        assertEquals(today.minusDays(1), state.date)
        assertEquals(LocalTime.of(9, 12, 37, 500_000_000), state.time)
        assertEquals("After lunch\n$said", state.notes)
        // Nothing changes in the journal until the patient saves
        advanceUntilIdle()
        assertEquals(logged, savedEntry())

        viewModel.save()
        advanceUntilIdle()
        val saved = savedEntry()
        assertEquals(listOf(SymptomType.FATIGUE, SymptomType.NAUSEA), saved.types)
        assertEquals(logged.occurredAt, saved.occurredAt)
        assertEquals("After lunch\n$said", saved.notes)
      }

  @Test
  fun aDictatedSymptom_isSavedOnlyWhenThePatientSaves() =
      runTest(dispatcher) {
        val viewModel = heard("headache 7 out of 10 yesterday at 9 am")
        viewModel.onDone()
        advanceUntilIdle()
        assertEquals(emptyList<SymptomEntry>(), repository.symptoms(uid))

        viewModel.save()
        advanceUntilIdle()

        val saved = savedEntry()
        assertEquals(listOf(SymptomType.HEADACHE), saved.types)
        assertEquals(7, saved.intensity)
        assertEquals(at(today.minusDays(1), 9, 0), saved.occurredAt)
        assertEquals("headache 7 out of 10 yesterday at 9 am", saved.notes)
      }

  @Test
  fun aFrenchPhone_listensAndUnderstandsInFrench() =
      runTest(dispatcher) {
        val viewModel = heard("j'ai mal dormi cette nuit", viewModel(Locale.FRANCE))

        assertEquals(listOf(SpeechLanguage.FRENCH), speech.languages)
        viewModel.onDone()
        assertEquals(listOf(SymptomType.SLEEP_PROBLEMS), viewModel.state.types)
      }

  // ----- Voice without a connection (US-45) -----

  @Test
  fun beforeListening_theDownloadIsOfferedOnlyWhenThePhoneCanDownloadTheLanguage() =
      runTest(dispatcher) {
        // What the phone says about English offline → what Speak shows, and whether it listens
        // with the on-device recognizer (null: it doesn't listen yet)
        val cases =
            listOf(
                Triple(
                    OfflineSpeech.DOWNLOADABLE,
                    VoiceUiState.OfferingOffline(SpeechLanguage.ENGLISH),
                    null,
                ),
                Triple(
                    OfflineSpeech.DOWNLOADING,
                    VoiceUiState.Listening(
                        connection = ConnectionNote.Downloading(SpeechLanguage.ENGLISH)
                    ),
                    false,
                ),
                Triple(OfflineSpeech.READY, VoiceUiState.Listening(), true),
                Triple(
                    OfflineSpeech.ONLINE_ONLY,
                    VoiceUiState.Listening(connection = ConnectionNote.OnlineOnly),
                    false,
                ),
                Triple(OfflineSpeech.UNKNOWN, VoiceUiState.Listening(), false),
            )
        for ((support, expected, onDevice) in cases) {
          speech.offline = support
          val viewModel = listening()

          assertEquals("$support", expected, viewModel.state.voice)
          assertEquals("$support", onDevice != null, speech.isListening)
          if (onDevice != null) assertEquals("$support", onDevice, speech.onDevice.last())
          viewModel.cancelVoice()
          advanceUntilIdle()
        }
        assertEquals(emptyList<SpeechLanguage>(), speech.downloads)
      }

  @Test
  fun acceptingTheOffer_downloadsTheLanguage_andListensSayingItIsDownloading() =
      runTest(dispatcher) {
        speech.offline = OfflineSpeech.DOWNLOADABLE
        val viewModel = listening(viewModel(Locale.FRANCE))
        assertEquals(VoiceUiState.OfferingOffline(SpeechLanguage.FRENCH), viewModel.state.voice)

        viewModel.onDownloadForOffline()
        advanceUntilIdle()

        assertEquals(listOf(SpeechLanguage.FRENCH), speech.downloads)
        assertTrue(speech.isListening)
        // The note stays while words come in and after starting over
        hear(SpeechEvent.PartialText("j'ai mal"))
        val downloading = ConnectionNote.Downloading(SpeechLanguage.FRENCH)
        assertEquals(downloading, (viewModel.state.voice as VoiceUiState.Listening).connection)
        viewModel.restartListening()
        advanceUntilIdle()
        assertEquals(VoiceUiState.Listening(connection = downloading), viewModel.state.voice)
        // Not on the phone yet: it listens with the default recognizer, which may go online
        assertEquals(listOf(false, false), speech.onDevice)
      }

  @Test
  fun howVoiceListens_followsWhatThePhoneSaysEachTimeSpeakIsTapped() =
      runTest(dispatcher) {
        speech.offline = OfflineSpeech.DOWNLOADABLE
        val viewModel = listening()
        viewModel.onDownloadForOffline()
        advanceUntilIdle()
        val downloading = ConnectionNote.Downloading(SpeechLanguage.ENGLISH)
        // The phone's answer on each later Speak → the note shown while listening, and whether it
        // listens on the phone; no answer (a stuck phone, null here) or "can't tell" keeps what was
        // known
        val cases =
            listOf(
                Triple(OfflineSpeech.UNKNOWN, downloading, false),
                Triple(null, downloading, false),
                Triple(OfflineSpeech.READY, null, true),
                Triple(null, null, true),
                Triple(OfflineSpeech.DOWNLOADING, downloading, false),
                Triple(OfflineSpeech.ONLINE_ONLY, ConnectionNote.OnlineOnly, false),
                Triple(OfflineSpeech.DOWNLOADABLE, null, false),
            )
        for ((answer, note, onDevice) in cases) {
          viewModel.cancelVoice()
          speech.offlineHangs = answer == null
          answer?.let { speech.offline = it }
          viewModel.onSpeak(hasMicrophone = true)
          advanceUntilIdle()
          // A phone that could download offers it again; declining listens without the note
          if (viewModel.state.voice is VoiceUiState.OfferingOffline) viewModel.onNotNow()
          advanceUntilIdle()

          assertEquals(
              "$answer",
              note,
              (viewModel.state.voice as VoiceUiState.Listening).connection,
          )
          assertEquals("$answer", onDevice, speech.onDevice.last())
        }
      }

  @Test
  fun whileThePhoneIsAsked_theScreenShowsAtOnce_andDoneWaits() =
      runTest(dispatcher) {
        speech.offlineHangs = true
        val viewModel = viewModel()

        viewModel.onSpeak(hasMicrophone = true)
        assertEquals(VoiceUiState.Preparing, viewModel.state.voice)
        viewModel.onDone()

        assertEquals(0, speech.stops)
        assertEquals(VoiceUiState.Preparing, viewModel.state.voice)
        assertFalse(speech.isListening)
      }

  @Test
  fun savingWhileThePhoneIsAsked_goesBackToTheForm_withoutListening() =
      runTest(dispatcher) {
        speech.offlineHangs = true
        val viewModel = viewModel()
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.onSpeak(hasMicrophone = true)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(SavePhase.SAVED, viewModel.state.save)
        assertNull(viewModel.state.voice)
        assertEquals(emptyList<SpeechLanguage>(), speech.languages)
      }

  @Test
  fun notNow_listensWithoutDownloading() =
      runTest(dispatcher) {
        speech.offline = OfflineSpeech.DOWNLOADABLE
        val viewModel = listening()

        viewModel.onNotNow()
        advanceUntilIdle()

        assertEquals(VoiceUiState.Listening(), viewModel.state.voice)
        assertTrue(speech.isListening)
        assertEquals(emptyList<SpeechLanguage>(), speech.downloads)
      }

  @Test
  fun theOfferButtons_doNothingOnceTheOfferIsGone() =
      runTest(dispatcher) {
        val viewModel = listening()
        hear(SpeechEvent.PartialText("I feel sick"))

        viewModel.onDownloadForOffline()
        viewModel.onNotNow()
        advanceUntilIdle()

        assertEquals("I feel sick", (viewModel.state.voice as VoiceUiState.Listening).text)
        assertEquals(emptyList<SpeechLanguage>(), speech.downloads)
        assertEquals(1, speech.languages.size)
      }

  @Test
  fun aPhoneSlowToAnswer_isNotWaitedFor_andASecondTapDoesNotAskAgain() =
      runTest(dispatcher) {
        speech.offlineHangs = true
        val viewModel = viewModel()

        viewModel.onSpeak(hasMicrophone = true)
        viewModel.onSpeak(hasMicrophone = true)
        advanceTimeBy(1_900)
        assertFalse(speech.isListening)
        advanceUntilIdle()

        assertEquals(VoiceUiState.Listening(), viewModel.state.voice)
        assertEquals(1, speech.languages.size)
      }

  @Test
  fun goingBackWhileThePhoneIsAsked_neverStartsListening() =
      runTest(dispatcher) {
        speech.offlineHangs = true
        val viewModel = viewModel()
        viewModel.onSpeak(hasMicrophone = true)

        viewModel.cancelVoice()
        advanceUntilIdle()

        assertNull(viewModel.state.voice)
        assertFalse(speech.isListening)
      }

  @Test
  fun dictating_isIgnoredWhileSaving() =
      runTest(dispatcher) {
        val viewModel = heard("I feel sick 7/10")
        viewModel.toggleType(SymptomType.PAIN)
        viewModel.save()

        // Saving: the form no longer changes
        viewModel.onDone()
        viewModel.onSpeak(hasMicrophone = true)
        assertEquals(listOf(SymptomType.PAIN), viewModel.state.types)
        assertNull(viewModel.state.intensity)
        advanceUntilIdle()
        assertEquals(SavePhase.SAVED, viewModel.state.save)
        assertEquals(1, speech.languages.size)
      }

  @Test
  fun leavingTheForm_stopsListening_andDropsWhatWasSaid() =
      runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = listening().also { store.put("form", it) }
        hear(SpeechEvent.PartialText("I feel sick"))
        assertTrue(speech.isListening)

        store.clear()
        advanceUntilIdle()

        assertFalse(speech.isListening)
        assertEquals(emptyList<SymptomEntry>(), repository.symptoms(uid))
        assertEquals(emptyList<SymptomType>(), viewModel.state.types)
      }

  // ----- A caregiver the patient lets edit and delete (US-46) -----

  /** The signed-in caregiver Sam, editing [entry] in "patient"'s journal. */
  private fun caregiverEditing(
      entry: SymptomEntry = logged,
      symptoms: FakeSymptomRepository = repository,
  ): AddSymptomViewModel {
    symptoms.seed("patient", entry)
    return AddSymptomViewModel(
        auth,
        symptoms,
        clock,
        symptomId = entry.id,
        patientUid = "patient",
        profileRepository =
            FakeUserProfileRepository().apply {
              seed(UserProfile(uid, Role.CAREGIVER, firstName = "Sam"))
            },
        speechRecognizer = speech,
    )
  }

  @Test
  fun caregiver_editsThePatientsSymptom_signedAsEditor_keepingWhoAddedIt() =
      runTest(dispatcher) {
        val viewModel = caregiverEditing(logged.copy(addedBy = "bob", addedByName = "Bob"))
        advanceUntilIdle()
        assertEquals(logged.types, viewModel.state.types)

        viewModel.onIntensityChange(6)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(SavePhase.SAVED, viewModel.state.save)
        assertTrue(repository.symptoms(uid).isEmpty())
        val saved = repository.symptoms("patient").single()
        assertEquals(6, saved.intensity)
        assertEquals(uid, saved.editedBy)
        assertEquals("Sam", saved.editedByName)
        assertEquals("bob", saved.addedBy)
        assertEquals("Bob", saved.addedByName)
      }

  @Test
  fun patientEdit_removesTheCaregiversName() =
      runTest(dispatcher) {
        val viewModel = editing(logged.copy(editedBy = "bob", editedByName = "Bob"))
        advanceUntilIdle()

        viewModel.onIntensityChange(2)
        viewModel.save()
        advanceUntilIdle()

        val saved = repository.symptoms(uid).single()
        assertEquals(2, saved.intensity)
        assertNull(saved.editedBy)
        assertNull(saved.editedByName)
      }

  /** Access taken away before an offline edit synchronizes: the server undoes it, and says so. */
  @Test
  fun caregiverEditRejectedByTheServer_isUndoneAndReported() =
      runTest(dispatcher) {
        val rejectedWrites = RejectedWrites()
        val symptoms = FakeSymptomRepository(clock = { current }, rejectedWrites = rejectedWrites)
        symptoms.serverRejectsWrites = true
        val viewModel = caregiverEditing(symptoms = symptoms)
        advanceUntilIdle()

        viewModel.onIntensityChange(6)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(SavePhase.SAVED, viewModel.state.save)
        symptoms.rejectPendingWrites()

        assertEquals(listOf(logged), symptoms.symptoms("patient"))
        assertEquals(1, rejectedWrites.unseen.value)
      }

  /**
   * The patient and the caregiver edit the same symptom: the form saves the whole entry it showed,
   * so the last save wins, as with the patient's own edits on two phones. Merging field by field
   * would need the server, which an offline save can't wait for.
   */
  @Test
  fun bothEditTheSameSymptom_theLastSaveWins() =
      runTest(dispatcher) {
        val viewModel = caregiverEditing()
        advanceUntilIdle()

        // The patient changes the intensity after the caregiver opened the form
        repository.updateSymptom("patient", logged.copy(intensity = 8))
        viewModel.onNotesChange("Slept badly")
        viewModel.save()
        advanceUntilIdle()

        val saved = repository.symptoms("patient").single()
        assertEquals("Slept badly", saved.notes)
        assertEquals(logged.intensity, saved.intensity)
        assertEquals(uid, saved.editedBy)
      }

  /** The patient deletes the symptom while the caregiver edits it: the save can't bring it back. */
  @Test
  fun symptomDeletedWhileTheCaregiverEditsIt_staysDeleted_andTheRejectionIsReported() =
      runTest(dispatcher) {
        val rejectedWrites = RejectedWrites()
        val symptoms = FakeSymptomRepository(clock = { current }, rejectedWrites = rejectedWrites)
        val viewModel = caregiverEditing(symptoms = symptoms)
        advanceUntilIdle()
        symptoms.deleteSymptom("patient", logged.id)

        // Saved on the phone, then refused by the server, which no longer has the symptom
        symptoms.serverRejectsWrites = true
        viewModel.onIntensityChange(6)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(SavePhase.SAVED, viewModel.state.save)
        symptoms.rejectPendingWrites()

        assertTrue(symptoms.symptoms("patient").isEmpty())
        assertEquals(1, rejectedWrites.unseen.value)
      }
}
