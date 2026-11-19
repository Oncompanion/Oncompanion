// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.speech.FakeSpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechEvent
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The add symptom screen with its ViewModel, dictating through a fake recognizer (US-44). */
@RunWith(AndroidJUnit4::class)
class VoiceSymptomScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val app: Application = ApplicationProvider.getApplicationContext()
  private val speech = FakeSpeechRecognizer()
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock =
      Clock.fixed(LocalDateTime.of(2026, 10, 5, 14, 0).atZone(zone).toInstant(), zone)
  private val auth =
      FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
        runBlocking { it.signInWithGoogle("token") }
      }
  private val viewModel =
      AddSymptomViewModel(
          auth,
          FakeSymptomRepository(clock = clock::instant),
          clock,
          speechRecognizer = speech,
          locale = Locale.ENGLISH,
      )

  private fun string(id: Int) = app.getString(id)

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private fun showScreen() {
    compose.setContent { AddSymptomScreen(viewModel = viewModel, onBack = {}, onSaved = {}) }
  }

  /** After the activity was recreated (a rotation): shows the screen again, same ViewModel. */
  private fun showScreenAgain() {
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread {
      compose.activity.setContent {
        AddSymptomScreen(viewModel = viewModel, onBack = {}, onSaved = {})
      }
    }
    compose.waitForIdle()
  }

  private fun microphone(allowed: Boolean) {
    val shadow = shadowOf(app)
    if (allowed) shadow.grantPermissions(Manifest.permission.RECORD_AUDIO)
    else shadow.denyPermissions(Manifest.permission.RECORD_AUDIO)
  }

  /** The phone hears [events]. */
  private fun hear(vararg events: SpeechEvent) {
    compose.runOnIdle { speech.send(*events) }
    compose.waitForIdle()
  }

  private fun speak() {
    node(C.Tag.symptom_add_speak).performClick()
    compose.waitForIdle()
  }

  @Test
  fun dictating_showsWhatIsPickedUp_thenDoneFillsTheForm() {
    microphone(allowed = true)
    showScreen()

    speak()
    node(C.Tag.symptom_voice_headline).assertTextEquals(string(R.string.symptom_voice_listening))
    hear(SpeechEvent.Listening, SpeechEvent.Level(0.6f), SpeechEvent.PartialText("I feel sick"))
    compose.onNodeWithText(app.getString(SymptomType.NAUSEA.label)).assertIsDisplayed()

    node(C.Tag.symptom_voice_done).performClick()
    assertEquals(1, speech.stops)
    hear(SpeechEvent.FinalText("I feel sick 7/10"))

    // Back on the form, filled and marked to check
    node(C.Tag.symptom_add_drafted).assertIsDisplayed()
    assertEquals(listOf(SymptomType.NAUSEA), viewModel.uiState.value.types)
    assertEquals(7, viewModel.uiState.value.intensity)
  }

  @Test
  fun withoutTheMicrophone_theAppExplainsFirst_andAsksAndroid() {
    microphone(allowed = false)
    showScreen()

    speak()
    node(C.Tag.symptom_voice_headline).assertTextEquals(string(R.string.symptom_voice_why_title))
    assertFalse(speech.isListening)

    node(C.Tag.symptom_voice_allow).performClick()
    val asked = shadowOf(compose.activity).lastRequestedPermission
    assertTrue(Manifest.permission.RECORD_AUDIO in asked.requestedPermissions)
  }

  @Test
  fun typeInstead_goesBackToTheForm() {
    microphone(allowed = false)
    showScreen()

    speak()
    node(C.Tag.symptom_voice_type_instead).performClick()

    node(C.Tag.symptom_add_speak).assertIsDisplayed()
  }

  @Test
  fun systemBack_whileListening_stopsAndGoesBackToTheForm() {
    microphone(allowed = true)
    showScreen()
    speak()
    assertTrue(speech.isListening)

    compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.waitForIdle()

    node(C.Tag.symptom_add_speak).assertIsDisplayed()
    assertFalse(speech.isListening)
  }

  @Test
  fun refusedMicrophone_opensTheSettings_andListensOnceAllowedThere() {
    microphone(allowed = false)
    showScreen()
    speak()
    compose.runOnIdle { viewModel.onMicrophoneAnswer(granted = false) }
    node(C.Tag.symptom_voice_headline)
        .assertTextEquals(string(R.string.symptom_voice_refused_title))

    node(C.Tag.symptom_voice_open_settings).performClick()
    val settings = shadowOf(compose.activity).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)

    // Allowed in the settings, then back in the app
    microphone(allowed = true)
    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()
    node(C.Tag.symptom_voice_headline).assertTextEquals(string(R.string.symptom_voice_listening))
    assertTrue(speech.isListening)
  }

  @Test
  fun rotating_keepsWhatWasSaid_andTheFilledForm() {
    microphone(allowed = true)
    showScreen()
    speak()
    hear(SpeechEvent.PartialText("I feel sick"))

    showScreenAgain()
    compose.onNodeWithText("“I feel sick”").assertIsDisplayed()

    node(C.Tag.symptom_voice_done).performClick()
    hear(SpeechEvent.FinalText("I feel sick"))
    showScreenAgain()
    node(C.Tag.symptom_add_drafted).assertIsDisplayed()
  }
}
