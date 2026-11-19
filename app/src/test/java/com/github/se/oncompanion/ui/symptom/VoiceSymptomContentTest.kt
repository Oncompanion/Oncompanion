// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.symptom.SymptomDraft
import com.github.se.oncompanion.model.speech.SpeechError
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class VoiceSymptomContentTest {

  @get:Rule val compose = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val today = LocalDate.of(2026, 10, 5)
  private val said = "Tired and a bit nauseous since yesterday at 9, maybe 5 out of 10"
  private val draft =
      SymptomDraft(
          types = listOf(SymptomType.FATIGUE, SymptomType.NAUSEA),
          intensity = 5,
          date = today.minusDays(1),
          time = LocalTime.of(9, 0),
          notes = said,
      )

  /** What the screen reported, in order: `close`, `allow`, `done`, `startOver`. */
  private val events = mutableListOf<String>()
  private var voice by mutableStateOf<VoiceUiState>(VoiceUiState.Listening())

  private fun show(state: VoiceUiState) {
    voice = state
    compose.setContent {
      VoiceSymptomContent(
          voice = voice,
          today = today,
          onClose = { events += "close" },
          onAllowMicrophone = { events += "allow" },
          onDone = { events += "done" },
          onStartOver = { events += "startOver" },
      )
    }
  }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  /** What a state shows: its headline, its text, and its buttons (tag → what tapping reports). */
  private data class Case(
      val state: VoiceUiState,
      val headline: Int,
      val text: Int?,
      val buttons: Map<String, String>,
  )

  private val typeInstead = C.Tag.symptom_voice_type_instead to "close"
  private val cases =
      listOf(
          Case(
              VoiceUiState.ExplainingMicrophone,
              R.string.symptom_voice_why_title,
              R.string.symptom_voice_why_message,
              mapOf(C.Tag.symptom_voice_allow to "allow", typeInstead),
          ),
          Case(
              VoiceUiState.Listening(),
              R.string.symptom_voice_listening,
              R.string.symptom_voice_hint,
              mapOf(C.Tag.symptom_voice_done to "done", typeInstead),
          ),
          Case(
              VoiceUiState.Heard(said, draft),
              R.string.symptom_voice_heard,
              null,
              mapOf(
                  C.Tag.symptom_voice_done to "done",
                  C.Tag.symptom_voice_start_over to "startOver",
                  typeInstead,
              ),
          ),
          // The microphone off and every failure: for now, typing is the way on
          Case(
              VoiceUiState.MicrophoneRefused,
              R.string.symptom_voice_failed_title,
              R.string.symptom_voice_failed_message,
              mapOf(typeInstead),
          ),
          Case(
              VoiceUiState.Failed(SpeechError.NO_SPEECH),
              R.string.symptom_voice_failed_title,
              R.string.symptom_voice_failed_message,
              mapOf(typeInstead),
          ),
      )

  private val allButtons =
      listOf(
          C.Tag.symptom_voice_allow,
          C.Tag.symptom_voice_done,
          C.Tag.symptom_voice_start_over,
          C.Tag.symptom_voice_type_instead,
      )

  @Test
  fun eachState_saysWhatIsHappening_andOffersItsButtons() {
    show(VoiceUiState.Listening())
    for (case in cases) {
      voice = case.state
      compose.waitForIdle()
      val name = case.state.toString()
      node(C.Tag.symptom_voice_headline).assertTextEquals(string(case.headline))
      case.text?.let { compose.onNodeWithText(string(it)).assertIsDisplayed() }
      for (tag in allButtons) {
        assertEquals(
            "$name: $tag",
            tag in case.buttons,
            compose.onAllNodesWithTagCount(tag) == 1,
        )
      }
      for ((tag, event) in case.buttons) {
        events.clear()
        node(tag).performClick()
        assertEquals(name, listOf(event), events)
      }
      // The way back to the form is always there
      events.clear()
      node(C.Tag.symptom_voice_close).performClick()
      assertEquals(name, listOf("close"), events)
    }
  }

  @Test
  fun whatWasSaid_andWhatWasPickedUp_areShown() {
    show(VoiceUiState.Listening(said, draft))

    compose.onNodeWithText(string(R.string.symptom_voice_transcript, said)).assertIsDisplayed()
    node(C.Tag.symptom_voice_picked_up).assertIsDisplayed()
    // The symptoms, the intensity, and when, as the form will show them
    val picked =
        listOf(
            string(SymptomType.FATIGUE.label),
            string(SymptomType.NAUSEA.label),
            string(R.string.symptom_voice_intensity, 5),
            "Yesterday · 09:00",
        )
    for (label in picked) compose.onNodeWithText(label).assertIsDisplayed()
  }

  @Test
  fun nothingUnderstood_showsTheWordsWithoutPickedUp() {
    show(VoiceUiState.Heard("Hello there", SymptomDraft(notes = "Hello there")))

    node(C.Tag.symptom_voice_transcript).assertIsDisplayed()
    node(C.Tag.symptom_voice_picked_up).assertDoesNotExist()
  }

  @Test
  fun aTimeSaidWithoutADay_isToday() {
    show(VoiceUiState.Listening("at 9", SymptomDraft(time = LocalTime.of(9, 0), notes = "at 9")))

    compose.onNodeWithText("Today · 09:00").assertIsDisplayed()
  }

  @Test
  fun afterDone_itSaysTheFormIsBeingFilled_andDoneWaits() {
    show(VoiceUiState.Listening(said, draft, isFinishing = true))

    node(C.Tag.symptom_voice_headline).assertTextEquals(string(R.string.symptom_voice_finishing))
    node(C.Tag.symptom_voice_done).assertIsNotEnabled()
  }

  /** Held sideways, the main action and the way out can be reached and are fully visible. */
  private fun assertActionsReachableSideways() {
    show(VoiceUiState.Heard(said, draft))
    for (tag in listOf(C.Tag.symptom_voice_done, C.Tag.symptom_voice_type_instead)) {
      node(tag).performScrollTo().assertIsFullyVisible()
    }
    node(C.Tag.symptom_voice_lake).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_actionsAreReachable() = assertActionsReachableSideways()

  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscape_actionsAreReachable() = assertActionsReachableSideways()

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(
      tag: String
  ) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
