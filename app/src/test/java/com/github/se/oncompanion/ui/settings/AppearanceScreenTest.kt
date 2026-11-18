// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.settings.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Verifies every visible preference state independently of Android storage. */
@RunWith(AndroidJUnit4::class)
class AppearanceScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun everyChoiceHasSingleSelectionAndEmitsItsOwnValue() {
    var choice by mutableStateOf(AppearancePreference.FOLLOW_DEVICE)
    compose.setContent {
      OncompanionTheme {
        AppearanceContent(SettingsUiState(DeviceSettings(choice)), {}, { choice = it }, {})
      }
    }
    for (value in AppearancePreference.entries) {
      compose.onNodeWithTag(appearanceTag(value)).performClick().assertIsSelected()
      AppearancePreference.entries
          .filter { it != value }
          .forEach { compose.onNodeWithTag(appearanceTag(it)).assertIsNotSelected() }
      compose
          .onAllNodesWithTag(C.Tag.appearance_selected, useUnmergedTree = true)
          .assertCountEquals(1)
      compose
          .onNode(
              hasTestTag(C.Tag.appearance_selected) and
                  hasAnyAncestor(hasTestTag(appearanceTag(value))),
              useUnmergedTree = true,
          )
          .assertExists()
      assertEquals(value, choice)
    }
  }

  @Test
  fun loadingReadFailureRetrySavingAndSaveFailureAreVisible() {
    var state by mutableStateOf(SettingsUiState())
    var retries = 0
    compose.setContent { OncompanionTheme { AppearanceContent(state, {}, {}, { retries++ }) } }
    compose.onNodeWithTag(C.Tag.settings_loading).assertIsDisplayed()
    compose.runOnIdle { state = SettingsUiState(readFailed = true) }
    compose.onNodeWithText("Couldn't read your preferences.").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_retry).performClick()
    assertEquals(1, retries)
    compose.runOnIdle {
      state = SettingsUiState(DeviceSettings(), savePhase = SettingsSavePhase.SAVING)
    }
    compose.onNodeWithTag(C.Tag.settings_loading).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.appearance_dark).assertIsNotEnabled()
    compose.runOnIdle {
      state = SettingsUiState(DeviceSettings(), savePhase = SettingsSavePhase.FAILED)
    }
    compose.onNodeWithTag(C.Tag.settings_save_error).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appearance_device).assertIsSelected()
  }

  @Test
  fun savingDoesNotMoveTheRowsOrShowAnError() {
    var state by mutableStateOf(SettingsUiState(DeviceSettings()))
    compose.setContent { OncompanionTheme { AppearanceContent(state, {}, {}, {}) } }
    val row = compose.onNodeWithTag(C.Tag.appearance_device)
    val before = row.fetchSemanticsNode().boundsInRoot
    compose.runOnIdle { state = state.copy(savePhase = SettingsSavePhase.SAVING) }
    row.assertIsNotEnabled()
    assertEquals(before, row.fetchSemanticsNode().boundsInRoot)
    compose.onNodeWithTag(C.Tag.settings_save_error).assertDoesNotExist()
    compose.onNodeWithText("""Use your phone's light or dark mode""").assertIsDisplayed()
    compose
        .onNodeWithText("Changes apply immediately across the app and are saved on this phone.")
        .assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun optionsAndBackAreReachableAt200PercentInLandscape() {
    compose.setContent {
      OncompanionTheme {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
          AppearanceContent(SettingsUiState(DeviceSettings()), {}, {}, {})
        }
      }
    }
    AppearancePreference.entries.forEach {
      compose.onNodeWithTag(appearanceTag(it)).performScrollTo().assertIsFullyVisible()
    }
    compose.onNodeWithTag(C.Tag.settings_back).assertIsFullyVisible()
  }

  @Test
  fun screenCollectsTheViewModelAndPersistsSelection() {
    val repo = FakeSettingsRepository()
    val vm = SettingsViewModel(repo)
    compose.setContent { OncompanionTheme { AppearanceScreen(vm, {}) } }
    compose.onNodeWithTag(C.Tag.appearance_dark).performClick()
    compose.onNodeWithTag(C.Tag.appearance_dark).assertIsSelected()
    compose.runOnIdle { assertEquals(AppearancePreference.DARK, repo.settings.value.appearance) }
  }
}
