// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ScanContentTest {

  @get:Rule val compose = createComposeRule()

  private var backs = 0
  private var manualEntries = 0
  private var settingsOpened = 0

  private fun string(id: Int) =
      ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

  private fun show(permission: CameraPermission) {
    compose.setContent {
      ScanContent(
          uiState = ScanUiState(permission = permission),
          onBack = { backs++ },
          onEnterManually = { manualEntries++ },
          onOpenSettings = { settingsOpened++ },
      )
    }
  }

  @Test
  fun whileThePermissionIsAsked_onlyTheTopBarIsShown() {
    show(CameraPermission.UNKNOWN)

    compose.onNodeWithText(string(R.string.scan_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_permission_pending).assertExists()
    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.scan_camera_off).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.scan_enter_manually_button).assertDoesNotExist()
  }

  @Test
  fun cameraAllowed_showsTheViewfinderStarting_withTheFramingHint() {
    show(CameraPermission.GRANTED)

    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_camera_starting).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.scan_frame_hint)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_camera_off).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.scan_open_settings_button).assertDoesNotExist()
  }

  @Test
  fun cameraRefused_explainsWhy_andOffersBothWaysForward() {
    show(CameraPermission.REFUSED)

    compose.onNodeWithTag(C.Tag.scan_camera_off).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.scan_camera_off_title)).assertIsDisplayed()
    compose
        .onNodeWithText(string(R.string.scan_camera_off_explanation), substring = true)
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_enter_manually_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_open_settings_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertDoesNotExist()
  }

  @Test
  fun cameraRefused_enterManually_opensManualEntry() {
    show(CameraPermission.REFUSED)

    compose.onNodeWithTag(C.Tag.scan_enter_manually_button).performClick()

    assertEquals(1, manualEntries)
    assertEquals(0, settingsOpened)
  }

  @Test
  fun cameraRefused_openSettings_opensTheSettings() {
    show(CameraPermission.REFUSED)

    compose.onNodeWithTag(C.Tag.scan_open_settings_button).performClick()

    assertEquals(1, settingsOpened)
    assertEquals(0, manualEntries)
  }

  @Test
  fun closeButton_leavesTheScreen_inEveryState() {
    var permission by mutableStateOf(CameraPermission.UNKNOWN)
    compose.setContent {
      ScanContent(
          uiState = ScanUiState(permission = permission),
          onBack = { backs++ },
          onEnterManually = {},
          onOpenSettings = {},
      )
    }

    for ((index, state) in CameraPermission.entries.withIndex()) {
      permission = state
      compose.onNodeWithTag(C.Tag.scan_close_button).performClick()
      assertEquals("close in state $state", index + 1, backs)
    }
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun screenIsDark_evenWhenThePhoneUsesTheLightTheme() {
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        ScanContent(
            uiState = ScanUiState(permission = CameraPermission.REFUSED),
            onBack = {},
            onEnterManually = {},
            onOpenSettings = {},
        )
      }
    }

    // Average luminance of the rendered screen, from 0 (black) to 1 (white)
    val pixels = compose.onNodeWithTag(C.Tag.scan_screen).captureToImage().toPixelMap()
    var sum = 0.0
    var count = 0
    for (x in 0 until pixels.width step 4) {
      for (y in 0 until pixels.height step 4) {
        val color = pixels[x, y]
        sum += 0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue
        count++
      }
    }
    val luminance = sum / count
    assertTrue("luminance was $luminance", luminance < 0.3)
  }
}
