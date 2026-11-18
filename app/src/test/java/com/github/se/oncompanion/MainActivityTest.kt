// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.ComponentName
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.settings.AppearancePreference
import com.github.se.oncompanion.model.settings.FakeSettingsRepository
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.speech.AndroidSpeechRecognizer
import com.github.se.oncompanion.model.speech.SpeechRecognizerProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.theme.AppAppearanceViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

  private val originalSettings = SettingsRepositoryProvider.repository
  private val deviceSettings = FakeSettingsRepository()
  private val originalAuth = AuthRepositoryProvider.repository
  private val originalSignOut = SignOutProvider.signOut

  @get:Rule(order = 0)
  val providers =
      object : ExternalResource() {
        override fun before() {
          SettingsRepositoryProvider.repository = deviceSettings
          val auth = FakeAuthRepository()
          AuthRepositoryProvider.repository = auth
          SignOutProvider.signOut = SignOut(auth, FakeLocalSessionRepository())
          SpeechRecognizerProvider.reset()
        }

        override fun after() {
          SettingsRepositoryProvider.repository = originalSettings
          AuthRepositoryProvider.repository = originalAuth
          SignOutProvider.signOut = originalSignOut
          SpeechRecognizerProvider.reset()
        }
      }

  @get:Rule(order = 1) val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launchingMainActivity_displaysMainContainer() {
    composeTestRule.onNodeWithTag(C.Tag.main_screen_container).assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_startsOnSignInScreen() {
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.sign_in_welcome))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_doesNotShowOtherScreens() {
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
  }

  @Test
  fun mainActivity_launchesWithSplashScreenTheme() {
    val activity = composeTestRule.activity
    val info =
        activity.packageManager.getActivityInfo(
            ComponentName(activity, MainActivity::class.java),
            0,
        )
    assertEquals(R.style.Theme_oncompanion_Starting, info.theme)
  }

  @Test
  fun mainActivity_resizesForTheKeyboardInsteadOfSlidingTheWindowUp() {
    val softInputMode = composeTestRule.activity.window.attributes.softInputMode
    assertEquals(
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
    )
  }

  @Test
  fun mainActivity_givesThePhonesSpeechRecognizerTheAppsContext() {
    assertTrue(SpeechRecognizerProvider.recognizer is AndroidSpeechRecognizer)
  }

  /** Activity recreation reloads the same device override while retaining the account policy. */
  @Test
  fun savedAppearanceSurvivesActivityRecreation() {
    runBlocking { deviceSettings.setAppearance(AppearancePreference.DARK) }
    composeTestRule.waitUntil {
      ViewModelProvider(composeTestRule.activity)[AppAppearanceViewModel::class.java]
          .uiState
          .value
          .appearance == AppearancePreference.DARK
    }
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.waitUntil {
      ViewModelProvider(composeTestRule.activity)[AppAppearanceViewModel::class.java]
          .uiState
          .value
          .appearance == AppearancePreference.DARK
    }
    composeTestRule.onNodeWithTag(C.Tag.main_screen_container).assertIsDisplayed()
  }
}
