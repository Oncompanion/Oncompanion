// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddSymptomScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Monday 5 October 2026 at 14:00. */
  private val today = LocalDate.of(2026, 10, 5)
  private val now = today.atTime(14, 0)

  private val empty = AddSymptomUiState(now = now)
  private val filled = empty.copy(types = listOf(SymptomType.FATIGUE))

  /** What the content reported, in order, e.g. `type=FATIGUE` or `save`. */
  private val events = mutableListOf<String>()

  private var uiState by mutableStateOf(empty)

  private lateinit var backDispatcher: OnBackPressedDispatcher

  /** Shows AddSymptomContent like the real screen does: typing updates the state. */
  private fun setContent(state: AddSymptomUiState) {
    uiState = state
    composeTestRule.setContent {
      backDispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
      // Stands for going back: only reached when the screen lets the system Back through
      BackHandler { events += "systemBack" }
      AddSymptomContent(
          uiState = uiState,
          onBack = { events += "close" },
          onToggleType = { events += "type=$it" },
          onIntensityChange = {
            events += "intensity=$it"
            uiState = uiState.copy(intensity = it)
          },
          onClearIntensity = { events += "clearIntensity" },
          onDateChange = { events += "date=$it" },
          onTimeChange = { events += "time=$it" },
          onNotesChange = {
            events += "notes=$it"
            uiState = uiState.copy(notes = it)
          },
          onSave = { events += "save" },
          onErrorShown = {
            events += "errorShown"
            uiState = uiState.copy(error = null)
          },
      )
    }
  }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun scrolledTo(tag: String) = node(tag).performScrollTo()

  /** The text shown in the "When" field. */
  private fun assertWhen(text: String) {
    scrolledTo(C.Tag.symptom_add_when).assert(hasText(text))
  }

  /** Taps the intensity track [x] pixels from its left edge, as computed from its size. */
  private fun tapTrack(x: androidx.compose.ui.test.TouchInjectionScope.() -> Float) {
    scrolledTo(C.Tag.symptom_add_intensity_track).performTouchInput { click(Offset(x(), centerY)) }
  }

  private fun waitForText(text: String) {
    composeTestRule.waitUntil(5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForNoText(text: String) {
    composeTestRule.waitUntil(10_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }
  }

  // ----- States -----

  @Test
  fun emptyForm_showsEveryField_andSaveIsDisabled() {
    setContent(empty)

    SymptomType.entries.forEach { type ->
      scrolledTo(C.Tag.symptomAddType(type.name)).assertIsNotSelected()
    }
    scrolledTo(C.Tag.symptom_add_intensity_value)
        .assertTextEquals(string(R.string.symptom_add_intensity_not_set))
    node(C.Tag.symptom_add_intensity_clear).assertDoesNotExist()
    assertWhen("${string(R.string.symptom_journal_today)} · 14:00")
    composeTestRule.onNodeWithText(string(R.string.symptom_add_date_time_hint)).assertExists()
    composeTestRule.onNodeWithText(string(R.string.symptom_add_in_future)).assertDoesNotExist()
    scrolledTo(C.Tag.symptom_add_notes)
    composeTestRule.onNodeWithText(string(R.string.symptom_add_notes_required)).assertDoesNotExist()
    node(C.Tag.symptom_add_save).assertIsNotEnabled()
    node(C.Tag.symptom_add_close).assertIsEnabled()
  }

  @Test
  fun pickedSymptoms_areSelected_andSaveIsEnabled() {
    setContent(empty.copy(types = listOf(SymptomType.PAIN, SymptomType.FEVER)))

    scrolledTo(C.Tag.symptomAddType(SymptomType.PAIN.name)).assertIsSelected()
    scrolledTo(C.Tag.symptomAddType(SymptomType.FEVER.name)).assertIsSelected()
    scrolledTo(C.Tag.symptomAddType(SymptomType.NAUSEA.name)).assertIsNotSelected()
    node(C.Tag.symptom_add_save).assertIsEnabled()
  }

  @Test
  fun pickedIntensity_showsItsSeverity_andClear() {
    setContent(filled.copy(intensity = 6))

    scrolledTo(C.Tag.symptom_add_intensity_value).assertTextEquals("6/10 · Moderate")
    scrolledTo(C.Tag.symptom_add_intensity_clear).assertIsDisplayed()
  }

  @Test
  fun otherWithoutNotes_asksWhichSymptom_andSaveIsDisabled() {
    setContent(empty.copy(types = listOf(SymptomType.OTHER)))

    scrolledTo(C.Tag.symptom_add_notes)
    composeTestRule.onNodeWithText(string(R.string.symptom_add_notes_required)).assertExists()
    // Material shows the placeholder once the field is focused
    node(C.Tag.symptom_add_notes).performClick()
    composeTestRule
        .onNodeWithText(string(R.string.symptom_add_notes_other_placeholder))
        .assertExists()
    node(C.Tag.symptom_add_save).assertIsNotEnabled()
  }

  @Test
  fun otherWithNotes_canBeSaved() {
    setContent(empty.copy(types = listOf(SymptomType.OTHER), notes = "Hiccups"))

    node(C.Tag.symptom_add_save).assertIsEnabled()
  }

  @Test
  fun aTimeInTheFuture_isFlagged_andSaveIsDisabled() {
    setContent(filled.copy(date = today, time = LocalTime.of(23, 0), isInFuture = true))

    scrolledTo(C.Tag.symptom_add_when).assert(hasText(string(R.string.symptom_add_in_future)))
    composeTestRule.onNodeWithText(string(R.string.symptom_add_date_time_hint)).assertDoesNotExist()
    node(C.Tag.symptom_add_save).assertIsNotEnabled()
  }

  @Test
  fun chosenDayAndTime_areShown_withoutTheNowHint() {
    setContent(filled.copy(date = LocalDate.of(2026, 10, 3), time = LocalTime.of(8, 5)))

    assertWhen("Sat 3 Oct · 08:05")
    composeTestRule.onNodeWithText(string(R.string.symptom_add_date_time_hint)).assertDoesNotExist()
  }

  @Test
  fun yesterday_isCalledYesterday() {
    setContent(filled.copy(date = today.minusDays(1)))

    assertWhen("${string(R.string.symptom_journal_yesterday)} · 14:00")
  }

  @Test
  fun aDayOfAnotherYear_showsTheYear() {
    setContent(filled.copy(date = LocalDate.of(2025, 10, 4)))

    assertWhen("Sat 4 Oct 2025 · 14:00")
  }

  @Test
  fun theTimeShown_followsTheStatesNow() {
    setContent(empty.copy(now = today.atTime(9, 41)))

    assertWhen("${string(R.string.symptom_journal_today)} · 09:41")
  }

  @Test
  fun saving_showsTheSpinner_andDisablesSaveAndClose() {
    setContent(filled.copy(isSaving = true))

    node(C.Tag.symptom_add_saving).assertIsDisplayed()
    node(C.Tag.symptom_add_save).assertIsNotEnabled()
    node(C.Tag.symptom_add_close).assertIsNotEnabled()
  }

  @Test
  fun saved_disablesClose() {
    setContent(filled.copy(isSaved = true))

    node(C.Tag.symptom_add_close).assertIsNotEnabled()
  }

  @Test
  fun saveFailure_isShownOnce() {
    setContent(filled.copy(error = AddSymptomError.SAVE_FAILED))
    val message = string(R.string.symptom_add_error_save_failed)

    waitForText(message)
    waitForNoText(message)

    assertEquals(listOf("errorShown"), events)
  }

  @Test
  fun signedOutError_hasItsOwnMessage() {
    setContent(filled.copy(error = AddSymptomError.NOT_SIGNED_IN))

    waitForText(string(R.string.symptom_add_error_not_signed_in))
  }

  // ----- System Back -----

  @Test
  fun systemBack_leaves_whileFillingTheForm() {
    setContent(filled)

    composeTestRule.runOnIdle { backDispatcher.onBackPressed() }

    assertEquals(listOf("systemBack"), events)
  }

  @Test
  fun systemBack_isIgnored_whileSavingAndOnceSaved() {
    setContent(filled.copy(isSaving = true))
    composeTestRule.runOnIdle { backDispatcher.onBackPressed() }

    uiState = filled.copy(isSaved = true)
    composeTestRule.runOnIdle { backDispatcher.onBackPressed() }

    assertTrue(events.isEmpty())
  }

  // ----- Events -----

  @Test
  fun tappingASymptom_reportsIt() {
    setContent(empty)

    scrolledTo(C.Tag.symptomAddType(SymptomType.NAUSEA.name)).performClick()

    assertEquals(listOf("type=NAUSEA"), events)
  }

  @Test
  fun movingTheSlider_reportsTheIntensity() {
    setContent(filled.copy(intensity = 3))

    scrolledTo(C.Tag.symptom_add_intensity).performSemanticsAction(SemanticsActions.SetProgress) {
      it(8f)
    }

    assertEquals(listOf("intensity=8"), events)
    node(C.Tag.symptom_add_intensity_value).assertTextEquals("8/10 · Severe")
  }

  @Test
  fun aScreenReader_canSetOne_whileNotSet() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_intensity).performSemanticsAction(SemanticsActions.SetProgress) {
      it(1f)
    }

    assertEquals("intensity=1", events.last())
    node(C.Tag.symptom_add_intensity_value).assertTextEquals("1/10 · Mild")
  }

  /** Where the slider draws the stop of [intensity], on a track [width] pixels wide. */
  private fun androidx.compose.ui.test.TouchInjectionScope.stopX(intensity: Int): Float {
    val inset = 8.dp.toPx()
    return inset + (intensity - 1) / 9f * (width - 2 * inset)
  }

  @Test
  fun tappingTheFirstStopWhileNotSet_setsOne() {
    setContent(filled)

    tapTrack { stopX(1) }

    assertEquals(listOf("intensity=1"), events)
  }

  @Test
  fun tappingAnotherStopWhileNotSet_setsThatStop() {
    setContent(filled)

    tapTrack { stopX(4) }

    assertEquals(listOf("intensity=4"), events)
  }

  @Test
  fun tappingTheLastStopWhileNotSet_setsTen() {
    setContent(filled)

    tapTrack { stopX(10) }

    assertEquals(listOf("intensity=10"), events)
  }

  @Test
  fun draggingFromNotSet_isFollowedByTheSlider() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_intensity_track).performTouchInput {
      down(Offset(stopX(1), centerY))
      moveTo(Offset(width / 2f, centerY))
      moveTo(Offset(width.toFloat(), centerY))
      up()
    }

    // The first touch sets 1, then the slider follows the finger to the end of the track (minus
    // the distance a drag needs to start)
    assertEquals("intensity=1", events.first())
    val last = events.last().removePrefix("intensity=").toInt()
    assertTrue("ended on $last", last >= 9)
  }

  @Test
  fun clear_reportsIt() {
    setContent(filled.copy(intensity = 4))

    scrolledTo(C.Tag.symptom_add_intensity_clear).performClick()

    assertEquals(listOf("clearIntensity"), events)
  }

  @Test
  fun typingNotes_reportsThem() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_notes).performTextInput("Hiccups")

    assertEquals(listOf("notes=Hiccups"), events)
  }

  @Test
  fun saveAndClose_reportTheirClicks() {
    setContent(filled)

    node(C.Tag.symptom_add_save).performClick()
    node(C.Tag.symptom_add_close).performClick()

    assertEquals(listOf("save", "close"), events)
  }

  @Test
  fun tappingWhen_picksTheDay_thenTheTime() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_when).performClick()
    node(C.Tag.symptom_add_date_picker).assertExists()
    node(C.Tag.symptom_add_picker_confirm).performClick()
    node(C.Tag.symptom_add_date_picker).assertDoesNotExist()
    node(C.Tag.symptom_add_time_picker).assertExists()
    node(C.Tag.symptom_add_picker_confirm).performClick()

    assertEquals(listOf("date=2026-10-05", "time=14:00"), events)
    node(C.Tag.symptom_add_time_picker).assertDoesNotExist()
  }

  @Test
  fun thePickers_startOnTheChosenDayAndTime() {
    setContent(filled.copy(date = LocalDate.of(2026, 10, 3), time = LocalTime.of(8, 5)))

    scrolledTo(C.Tag.symptom_add_when).performClick()
    node(C.Tag.symptom_add_picker_confirm).performClick()
    node(C.Tag.symptom_add_picker_confirm).performClick()

    assertEquals(listOf("date=2026-10-03", "time=08:05"), events)
  }

  @Test
  fun cancellingTheDay_reportsNothing() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_when).performClick()
    node(C.Tag.symptom_add_picker_cancel).performClick()

    assertTrue(events.isEmpty())
    node(C.Tag.symptom_add_date_picker).assertDoesNotExist()
    node(C.Tag.symptom_add_time_picker).assertDoesNotExist()
  }

  @Test
  fun cancellingTheTime_keepsTheConfirmedDay() {
    setContent(filled)

    scrolledTo(C.Tag.symptom_add_when).performClick()
    node(C.Tag.symptom_add_picker_confirm).performClick()
    node(C.Tag.symptom_add_picker_cancel).performClick()

    assertEquals(listOf("date=2026-10-05"), events)
    node(C.Tag.symptom_add_time_picker).assertDoesNotExist()
  }

  // ----- Screen with its ViewModel -----

  @Test
  fun screen_showsTheViewModelsTime_savesThePickedSymptoms_andLeaves() {
    val zone = ZoneId.of("Europe/Zurich")
    val clock = Clock.fixed(now.atZone(zone).toInstant(), zone)
    val repository = FakeSymptomRepository(clock = clock::instant)
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val viewModel = AddSymptomViewModel(auth, repository, clock)
    var savedCount = 0
    composeTestRule.setContent {
      AddSymptomScreen(viewModel = viewModel, onBack = {}, onSaved = { savedCount++ })
    }

    assertWhen("${string(R.string.symptom_journal_today)} · 14:00")
    scrolledTo(C.Tag.symptomAddType(SymptomType.FATIGUE.name)).performClick()
    scrolledTo(C.Tag.symptom_add_intensity).performSemanticsAction(SemanticsActions.SetProgress) {
      it(7f)
    }
    node(C.Tag.symptom_add_save).performClick()
    composeTestRule.waitForIdle()

    assertEquals(1, savedCount)
    val entry = repository.symptoms("alice").single()
    assertEquals(listOf(SymptomType.FATIGUE), entry.types)
    assertEquals(7, entry.intensity)
    assertEquals(clock.instant(), entry.occurredAt)
  }
}
