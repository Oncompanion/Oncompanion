// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
  private var captures = 0
  private var messagesShown = 0

  private val camera = FakeScanCamera()

  private fun string(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

  private fun show(state: ScanUiState) {
    compose.setContent {
      ScanContent(
          uiState = state,
          onBack = { backs++ },
          onEnterManually = { manualEntries++ },
          onOpenSettings = { settingsOpened++ },
          onCapture = { captures++ },
          onMessageShown = { messagesShown++ },
          viewfinder = { camera.Viewfinder(it) },
      )
    }
  }

  private fun granted(camera: CameraStatus = CameraStatus.READY) =
      ScanUiState(permission = CameraPermission.GRANTED, camera = camera)

  // ---- permission ----

  @Test
  fun whileThePermissionIsAsked_onlyTheTopBarIsShown() {
    show(ScanUiState(permission = CameraPermission.UNKNOWN))

    compose.onNodeWithText(string(R.string.prescription_scan_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_permission_pending).assertExists()
    compose.onNodeWithTag(C.Tag.prescription_scan_viewfinder).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_camera_off).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertDoesNotExist()
  }

  @Test
  fun cameraRefused_explainsWhy_andOffersBothWaysForward() {
    show(ScanUiState(permission = CameraPermission.REFUSED))

    compose.onNodeWithTag(C.Tag.prescription_scan_camera_off).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_scan_camera_off_title)).assertIsDisplayed()
    compose
        .onNodeWithText(string(R.string.prescription_scan_camera_off_explanation), substring = true)
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_open_settings_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_viewfinder).assertDoesNotExist()
  }

  @Test
  fun cameraRefused_enterManually_opensManualEntry() {
    show(ScanUiState(permission = CameraPermission.REFUSED))

    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).performClick()

    assertEquals(1, manualEntries)
    assertEquals(0, settingsOpened)
  }

  @Test
  fun cameraRefused_openSettings_opensTheSettings() {
    show(ScanUiState(permission = CameraPermission.REFUSED))

    compose.onNodeWithTag(C.Tag.prescription_scan_open_settings_button).performClick()

    assertEquals(1, settingsOpened)
    assertEquals(0, manualEntries)
  }

  // ---- camera ----

  @Test
  fun cameraStarting_showsASpinner_andThePhotoCantBeTakenYet() {
    show(granted(CameraStatus.STARTING))

    compose.onNodeWithTag(C.Tag.prescription_scan_camera_starting).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_scan_frame_hint)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsNotEnabled()
    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_framing_guide).assertDoesNotExist()
  }

  @Test
  fun cameraReady_showsWhatTheCameraSees_andThePhotoCanBeTaken() {
    show(granted())

    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()
    // The guide the hint talks about ("inside the frame") is drawn over the preview
    compose.onNodeWithTag(C.Tag.prescription_scan_framing_guide).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_camera_starting).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsEnabled()
    compose.onNodeWithTag(C.Tag.prescription_scan_camera_off).assertDoesNotExist()
  }

  @Test
  fun shutter_takesAPhoto_andIsAnnouncedToScreenReaders() {
    show(granted())

    compose
        .onNodeWithContentDescription(string(R.string.prescription_scan_take_photo))
        .performClick()

    assertEquals(1, captures)
  }

  @Test
  fun whileThePhotoIsTaken_theShutterShowsProgress_andIsDisabled() {
    show(granted().copy(isCapturing = true))

    compose.onNodeWithTag(C.Tag.prescription_scan_capturing).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsNotEnabled()
  }

  @Test
  fun cameraUnavailable_explainsIt_andOffersOnlyManualEntry() {
    show(granted(CameraStatus.UNAVAILABLE))

    compose
        .onNodeWithText(string(R.string.prescription_scan_camera_unavailable_title))
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).assertIsDisplayed()
    // The settings can't help when the phone has no camera
    compose.onNodeWithTag(C.Tag.prescription_scan_open_settings_button).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertDoesNotExist()
  }

  // ---- messages ----

  @Test
  fun photoTaken_isSaidOnce() {
    show(granted().copy(photoTaken = true))

    compose.onNodeWithText(string(R.string.prescription_scan_photo_taken)).assertIsDisplayed()
    compose.mainClock.advanceTimeBy(10_000)
    compose.waitForIdle()
    assertEquals(1, messagesShown)
  }

  @Test
  fun failedCapture_isSaid() {
    show(granted().copy(captureFailed = true))

    compose.onNodeWithText(string(R.string.prescription_scan_capture_failed)).assertIsDisplayed()
  }

  // ---- leaving ----

  @Test
  fun closeButton_leavesTheScreen_inEveryState() {
    val states =
        listOf(
            ScanUiState(permission = CameraPermission.UNKNOWN),
            ScanUiState(permission = CameraPermission.REFUSED),
            granted(CameraStatus.STARTING),
            granted(),
            granted(CameraStatus.UNAVAILABLE),
        )
    var state by mutableStateOf(states.first())
    compose.setContent {
      ScanContent(
          uiState = state,
          onBack = { backs++ },
          onEnterManually = {},
          onOpenSettings = {},
      )
    }

    for ((index, s) in states.withIndex()) {
      state = s
      compose.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()
      assertEquals("close in state $s", index + 1, backs)
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
    val pixels = compose.onNodeWithTag(C.Tag.prescription_scan_screen).captureToImage().toPixelMap()
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
