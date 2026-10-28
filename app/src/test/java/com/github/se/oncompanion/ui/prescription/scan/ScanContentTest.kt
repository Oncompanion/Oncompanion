// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
  private var retakes = 0
  private var usedPhotos = 0

  @get:Rule val temporaryFolder = TemporaryFolder()

  /** A real JPEG, as the camera would write it. */
  private fun photoFile(width: Int = 40, height: Int = 60): File =
      temporaryFolder.newFile().apply {
        outputStream().use {
          Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
              .compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
      }

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
          onRetake = { retakes++ },
          onUsePhoto = { usedPhotos++ },
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
    // The preview is already there, as the camera only opens once it can draw it
    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertExists()
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
  fun failedCapture_isSaidOnce() {
    show(granted().copy(captureFailed = true))

    compose.onNodeWithText(string(R.string.prescription_scan_capture_failed)).assertIsDisplayed()
    compose.mainClock.advanceTimeBy(10_000)
    compose.waitForIdle()
    assertEquals(1, messagesShown)
  }

  // ---- photo preview ----

  @Test
  fun photoTaken_isShownToBeChecked_insteadOfTheCamera() {
    show(granted().copy(photo = photoFile()))

    compose.onNodeWithTag(C.Tag.prescription_scan_photo_preview).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_scan_check_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_scan_check_hint)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertDoesNotExist()
    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertDoesNotExist()
  }

  @Test
  fun retake_isSentToTheViewModel() {
    show(granted().copy(photo = photoFile()))

    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).performClick()

    assertEquals(1, retakes)
  }

  @Test
  fun usePhoto_isSentToTheViewModel() {
    show(granted().copy(photo = photoFile()))

    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).performClick()

    assertEquals(1, usedPhotos)
    assertEquals(0, retakes)
  }

  // ---- reading the prescription ----

  @Test
  fun whileReading_theUserIsToldItTakesAFewSeconds_andCanOnlyClose() {
    show(granted().copy(photo = photoFile(), isReading = true))

    compose.onNodeWithText(string(R.string.prescription_scan_reading_title)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_reading).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.prescription_scan_reading)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).assertDoesNotExist()

    compose.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()
    assertEquals(1, backs)
  }

  @Test
  fun nothingRead_offersManualEntryFirst_thenARetake() {
    show(granted().copy(photo = photoFile(), nothingRead = true))

    compose
        .onNodeWithText(string(R.string.prescription_scan_nothing_read_title))
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_nothing_read).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).assertDoesNotExist()

    compose
        .onNodeWithTag(C.Tag.prescription_scan_nothing_read_enter_manually_button)
        .assertTextEquals(string(R.string.prescription_scan_enter_it_manually))
        .performClick()
    compose.onNodeWithTag(C.Tag.prescription_scan_nothing_read_retake_button).performClick()

    assertEquals(1, manualEntries)
    assertEquals(1, retakes)
  }

  @Test
  fun photoThatCantBeRead_asksToRetakeIt() {
    show(granted().copy(photo = temporaryFolder.newFile("broken.jpg").apply { writeText("x") }))

    compose.waitUntil(5_000) {
      compose
          .onAllNodesWithTag(C.Tag.prescription_scan_photo_unreadable)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.prescription_scan_photo).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).assertIsDisplayed()
  }

  @Test
  fun photo_isReadOnTheGivenDispatcher() {
    val dispatcher = StandardTestDispatcher()
    val photo = temporaryFolder.newFile("broken.jpg").apply { writeText("x") }
    compose.setContent {
      ScanContent(
          uiState = granted().copy(photo = photo),
          onBack = {},
          onEnterManually = {},
          onOpenSettings = {},
          photoDispatcher = dispatcher,
      )
    }
    compose.waitForIdle()

    // Not read until the dispatcher runs: the preview is still loading
    compose.onNodeWithTag(C.Tag.prescription_scan_photo_unreadable).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).assertIsDisplayed()

    dispatcher.scheduler.advanceUntilIdle()
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_photo_unreadable).assertIsDisplayed()
  }

  @Test
  fun photoThatIsGone_asksToRetakeIt() {
    show(granted().copy(photo = File(temporaryFolder.root, "missing.jpg")))

    compose.waitUntil(5_000) {
      compose
          .onAllNodesWithTag(C.Tag.prescription_scan_photo_unreadable)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).assertIsDisplayed()
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun photo_isShown_evenAFullSizeOne() {
    var shown by mutableStateOf(photoFile())
    compose.setContent {
      ScanContent(
          uiState = granted().copy(photo = shown),
          onBack = {},
          onEnterManually = {},
          onOpenSettings = {},
      )
    }

    // A small photo, then a 12-megapixel one as a phone camera takes it
    for (photo in listOf(shown, photoFile(width = 4000, height = 3000))) {
      shown = photo

      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(C.Tag.prescription_scan_photo).fetchSemanticsNodes().isNotEmpty()
      }
      compose
          .onNodeWithContentDescription(string(R.string.prescription_scan_photo_description))
          .assertIsDisplayed()
      compose.onNodeWithTag(C.Tag.prescription_scan_photo_unreadable).assertDoesNotExist()
    }
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
            granted().copy(photo = photoFile()),
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
