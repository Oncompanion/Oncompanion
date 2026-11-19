// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Both settings destinations retain their observed preference across Activity recreation. */
@RunWith(AndroidJUnit4::class)
class SettingsRotationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /**
   * Recreating Appearance keeps the patient's selection and Settings shows the same saved value.
   */
  @Test
  fun chosenAppearanceAndItsSummarySurviveRecreation() {
    val model = SettingsViewModel(FakeSettingsRepository())
    compose.setContent { OncompanionTheme { AppearanceScreen(model, {}) } }
    compose.onNodeWithTag(C.Tag.appearance_dark).performClick()
    compose.onNodeWithTag(C.Tag.appearance_dark).assertIsSelected()
    compose.activityRule.scenario.recreate()
    compose.activity.setContent { OncompanionTheme { AppearanceScreen(model, {}) } }
    compose.onNodeWithTag(C.Tag.appearance_dark).assertIsSelected()
    compose.activity.setContent { OncompanionTheme { SettingsScreen(model, {}, {}) } }
    compose.onNodeWithText("Dark").assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    compose.activity.setContent { OncompanionTheme { SettingsScreen(model, {}, {}) } }
    compose.onNodeWithText("Dark").assertIsDisplayed()
  }
}
