// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.scan.DraftPrescriptionFromScan
import com.github.se.oncompanion.model.medication.scan.FakeTextRecognizer
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.model.medication.scan.ScannedSheets
import com.github.se.oncompanion.resources.C
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [ScanScreen] with Android's real permission flow (simulated by Robolectric) and a fake camera.
 */
@RunWith(AndroidJUnit4::class)
class ScanScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val camera = FakeScanCamera()
  private lateinit var cacheDir: File
  private lateinit var viewModel: ScanViewModel
  private var backs = 0
  private var manualEntries = 0
  private val reads = mutableListOf<ReadScan>()
  // Reads sheet 01 of the test prescriptions, unless a test says otherwise
  private val recognizer = FakeTextRecognizer(ScannedSheets.sheet(1))

  private val photosOnDisk: List<File>
    get() = File(cacheDir, ScanPhotoStore.DIRECTORY).listFiles()?.toList().orEmpty()

  private fun grantCamera() {
    shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
  }

  private fun showScreen() {
    cacheDir = temporaryFolder.newFolder("cache")
    viewModel = ScanViewModel(ScanPhotoStore(cacheDir), DraftPrescriptionFromScan(recognizer))
    compose.setContent {
      ScanScreen(
          onEnterManually = { manualEntries++ },
          onBack = { backs++ },
          onRead = { reads += it },
          viewModel = viewModel,
          camera = camera,
      )
    }
    compose.waitForIdle()
  }

  /** Answers Android's permission dialog that the screen opened. */
  private fun answerDialog(granted: Boolean) {
    val request = shadowOf(compose.activity).lastRequestedPermission
    assertNotNull("the screen should have asked for the camera", request)
    if (granted) grantCamera()
    val result =
        if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    compose.runOnUiThread {
      @Suppress("DEPRECATION")
      compose.activity.onRequestPermissionsResult(
          request!!.requestCode,
          request.requestedPermissions,
          intArrayOf(result),
      )
    }
    compose.waitForIdle()
  }

  private fun comeBackToTheApp() {
    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()
  }

  // ---- permission ----

  @Test
  fun cameraAlreadyAllowed_turnsTheCameraOn_withoutAskingAgain() {
    grantCamera()

    showScreen()

    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()
    assertEquals(1, camera.starts)
    assertNull(shadowOf(compose.activity).lastRequestedPermission)
  }

  @Test
  fun firstOpen_asksForTheCamera_beforeTurningItOn() {
    showScreen()

    val request = shadowOf(compose.activity).lastRequestedPermission
    assertEquals(listOf(Manifest.permission.CAMERA), request?.requestedPermissions?.toList())
    assertTrue(viewModel.uiState.value.permissionRequested)
    compose.onNodeWithTag(C.Tag.prescription_scan_permission_pending).assertExists()
    assertEquals(0, camera.starts)
  }

  @Test
  fun phoneRotated_whileTheDialogIsOpen_doesNotAskAgain() {
    showScreen()
    assertNotNull(shadowOf(compose.activity).lastRequestedPermission)

    // Rotating recreates the activity, but the screen's ViewModel survives it
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread {
      compose.activity.setContent {
        ScanScreen(
            onEnterManually = {},
            onBack = {},
            onRead = {},
            viewModel = viewModel,
            camera = camera,
        )
      }
    }
    compose.waitForIdle()

    assertNull(shadowOf(compose.activity).lastRequestedPermission)
    compose.onNodeWithTag(C.Tag.prescription_scan_permission_pending).assertExists()
  }

  @Test
  fun allowingTheCameraInTheDialog_turnsItOn() {
    showScreen()

    answerDialog(granted = true)

    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()
    assertEquals(1, camera.starts)
  }

  @Test
  fun refusingTheCameraInTheDialog_explainsWhyAndOffersManualEntry() {
    showScreen()

    answerDialog(granted = false)

    compose.onNodeWithTag(C.Tag.prescription_scan_camera_off).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).performClick()
    assertEquals(1, manualEntries)
    assertEquals(0, camera.starts)
  }

  @Test
  fun openSettings_opensThisAppsPageInThePhoneSettings() {
    showScreen()
    answerDialog(granted = false)

    compose.onNodeWithTag(C.Tag.prescription_scan_open_settings_button).performClick()

    val intent = shadowOf(compose.activity).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:${compose.activity.packageName}", intent.dataString)
  }

  @Test
  fun cameraAllowedInSettings_turnsTheCameraOnWhenComingBack() {
    showScreen()
    answerDialog(granted = false)

    grantCamera()
    comeBackToTheApp()

    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()
  }

  @Test
  fun comingBackWithoutAllowingTheCamera_keepsTheExplanation() {
    showScreen()
    answerDialog(granted = false)

    comeBackToTheApp()

    compose.onNodeWithTag(C.Tag.prescription_scan_camera_off).assertIsDisplayed()
    assertEquals(CameraPermission.REFUSED, viewModel.uiState.value.permission)
  }

  // ---- camera ----

  @Test
  fun phoneWithoutAUsableCamera_offersManualEntry() {
    grantCamera()
    camera.startError = IllegalStateException("This phone has no camera")

    showScreen()

    compose
        .onNodeWithText(
            compose.activity.getString(R.string.prescription_scan_camera_unavailable_title)
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).assertIsDisplayed()
  }

  @Test
  fun phoneRotated_noPhotoCanBeTaken_untilTheNewCameraIsOpen() {
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsEnabled()

    // Rotating recreates the screen with a new camera, still opening; the ViewModel survives it
    val newCamera = FakeScanCamera().apply { opening = CompletableDeferred() }
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread {
      compose.activity.setContent {
        ScanScreen(
            onEnterManually = {},
            onBack = {},
            onRead = {},
            viewModel = viewModel,
            camera = newCamera,
        )
      }
    }
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsNotEnabled()

    newCamera.opening?.complete(Unit)
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsEnabled()
  }

  @Test
  fun cameraFailingOnceOpen_offersManualEntry() {
    grantCamera()
    showScreen()
    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()

    camera.fail()
    compose.waitForIdle()

    compose
        .onNodeWithText(
            compose.activity.getString(R.string.prescription_scan_camera_unavailable_title)
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertDoesNotExist()
  }

  @Test
  fun shutter_takesAPhotoIntoTheScansFolder() {
    grantCamera()
    showScreen()

    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()

    assertEquals(photosOnDisk, camera.photos)
    assertEquals(1, photosOnDisk.size)
    // The photo is shown to be checked, instead of the camera
    compose.onNodeWithTag(C.Tag.prescription_scan_photo_preview).assertIsDisplayed()
  }

  @Test
  fun retake_deletesThePhoto_andShowsTheCameraAgain() {
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).performClick()
    compose.waitForIdle()

    assertTrue(photosOnDisk.isEmpty())
    compose.onNodeWithTag(FakeScanCamera.VIEWFINDER).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).assertIsDisplayed()
  }

  @Test
  fun usePhoto_readsThePrescription_andOpensTheFormWithItOnce() {
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).performClick()
    compose.waitUntil(5_000) { reads.isNotEmpty() }
    compose.waitForIdle()

    val read = reads.single()
    assertEquals("Dr. Anna Martin Oncology", read.prescription.draft.prescribedBy)
    // The photo is the form's now: kept until it is saved or closed
    assertTrue(read.photo.exists())
    assertNull(viewModel.uiState.value.read)
  }

  @Test
  fun closingWhileReading_deletesThePhoto_andNeverOpensTheForm() {
    recognizer.reading = CompletableDeferred()
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.prescription_scan_reading).assertExists()

    compose.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()
    recognizer.reading!!.complete(Unit)
    compose.waitForIdle()

    assertEquals(1, backs)
    assertTrue(photosOnDisk.isEmpty())
    assertTrue(reads.isEmpty())
  }

  @Test
  fun nothingRead_enterManually_deletesThePhoto() {
    recognizer.result = FakeTextRecognizer.NO_TEXT
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(C.Tag.prescription_scan_use_photo_button).performClick()
    compose.waitUntil(5_000) {
      compose
          .onAllNodesWithTag(C.Tag.prescription_scan_nothing_read)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    compose.onNodeWithTag(C.Tag.prescription_scan_nothing_read_enter_manually_button).performClick()
    compose.waitForIdle()

    assertEquals(1, manualEntries)
    assertTrue(photosOnDisk.isEmpty())
    assertTrue(reads.isEmpty())
  }

  @Test
  fun failedPhoto_isSaid_andLeavesNoFile() {
    grantCamera()
    camera.photoError = IOException("camera error")
    showScreen()

    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()

    assertTrue(photosOnDisk.isEmpty())
    compose
        .onNodeWithText(compose.activity.getString(R.string.prescription_scan_capture_failed))
        .assertIsDisplayed()
  }

  // ---- leaving ----

  @Test
  fun closing_deletesThePhoto_andLeaves() {
    grantCamera()
    showScreen()
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()

    assertEquals(1, backs)
    assertTrue(photosOnDisk.isEmpty())
  }

  @Test
  fun statusBarIcons_areLightOnTheDarkScanScreen_andRestoredWhenLeaving() {
    grantCamera()
    val controller = {
      WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
    }
    compose.runOnUiThread { controller().isAppearanceLightStatusBars = true }
    var showScan by mutableStateOf(true)
    cacheDir = temporaryFolder.newFolder("cache")
    viewModel = ScanViewModel(ScanPhotoStore(cacheDir), DraftPrescriptionFromScan(recognizer))
    compose.setContent {
      if (showScan)
          ScanScreen(
              onEnterManually = {},
              onBack = {},
              onRead = {},
              viewModel = viewModel,
              camera = camera,
          )
    }
    compose.waitForIdle()

    assertFalse(controller().isAppearanceLightStatusBars)

    showScan = false
    compose.waitForIdle()

    assertTrue(controller().isAppearanceLightStatusBars)
  }

  @Test
  fun leavingTheScreen_turnsTheCameraOff() {
    grantCamera()
    showScreen()

    compose.activityRule.scenario.moveToState(Lifecycle.State.DESTROYED)
    // Leaving cancels the camera's coroutine, which then finishes on the test's dispatcher
    compose.waitForIdle()

    assertEquals(1, camera.stops)
  }
}
