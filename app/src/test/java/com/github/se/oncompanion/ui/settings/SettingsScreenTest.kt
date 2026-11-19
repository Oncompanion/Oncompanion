// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Checks the Settings shell before preference categories are implemented. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun emptySettings_hasTitleAndBackWithoutUnbuiltCategories() {
    var backs = 0
    compose.setContent { OncompanionTheme { SettingsScreen(onBack = { backs++ }) } }
    compose.onNodeWithText("Settings").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_empty).assertIsDisplayed()
    listOf("Appearance", "Language", "Date and time", "Notifications").forEach {
      compose.onNodeWithText(it).assertDoesNotExist()
    }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    assertEquals(1, backs)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeAt200Percent_keepsMessageAndBackReachable() {
    compose.setContent {
      OncompanionTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
          SettingsContent(onBack = {})
        }
      }
    }
    compose.onNodeWithTag(C.Tag.settings_empty).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }
}
