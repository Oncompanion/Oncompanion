// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** [ScanScreen] with Android's real permission flow, simulated by Robolectric. */
@RunWith(AndroidJUnit4::class)
class ScanScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val viewModel = ScanViewModel()

  private fun grantCamera() {
    shadowOf(compose.activity.application).grantPermissions(Manifest.permission.CAMERA)
  }

  private fun showScreen() {
    compose.setContent { ScanScreen(onEnterManually = {}, onBack = {}, viewModel = viewModel) }
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

  @Test
  fun cameraAlreadyAllowed_showsTheCamera_withoutAskingAgain() {
    grantCamera()

    showScreen()

    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertIsDisplayed()
    assertNull(shadowOf(compose.activity).lastRequestedPermission)
  }

  @Test
  fun statusBarIcons_areLightOnTheDarkScanScreen_andRestoredWhenLeaving() {
    val controller = {
      WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
    }
    compose.runOnUiThread { controller().isAppearanceLightStatusBars = true }
    var showScan by mutableStateOf(true)
    compose.setContent {
      if (showScan) ScanScreen(onEnterManually = {}, onBack = {}, viewModel = viewModel)
    }
    compose.waitForIdle()

    assertFalse(controller().isAppearanceLightStatusBars)

    showScan = false
    compose.waitForIdle()

    assertTrue(controller().isAppearanceLightStatusBars)
  }

  @Test
  fun firstOpen_asksForTheCamera() {
    showScreen()

    val request = shadowOf(compose.activity).lastRequestedPermission
    assertEquals(listOf(Manifest.permission.CAMERA), request?.requestedPermissions?.toList())
    assertTrue(viewModel.uiState.value.permissionRequested)
    compose.onNodeWithTag(C.Tag.scan_permission_pending).assertExists()
  }

  @Test
  fun allowingTheCameraInTheDialog_showsTheCamera() {
    showScreen()

    answerDialog(granted = true)

    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertIsDisplayed()
  }

  @Test
  fun refusingTheCameraInTheDialog_explainsWhyAndOffersManualEntry() {
    showScreen()

    answerDialog(granted = false)

    compose.onNodeWithTag(C.Tag.scan_camera_off).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.scan_enter_manually_button).assertIsDisplayed()
  }

  @Test
  fun openSettings_opensThisAppsPageInThePhoneSettings() {
    showScreen()
    answerDialog(granted = false)

    compose.onNodeWithTag(C.Tag.scan_open_settings_button).performClick()

    val intent = shadowOf(compose.activity).nextStartedActivity
    assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
    assertEquals("package:${compose.activity.packageName}", intent.dataString)
  }

  @Test
  fun cameraAllowedInSettings_showsTheCameraWhenComingBack() {
    showScreen()
    answerDialog(granted = false)

    // The user allows the camera in the settings, then comes back to the app
    grantCamera()
    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.scan_viewfinder).assertIsDisplayed()
  }

  @Test
  fun comingBackWithoutAllowingTheCamera_keepsTheExplanation() {
    showScreen()
    answerDialog(granted = false)

    compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
    compose.waitForIdle()

    compose.onNodeWithTag(C.Tag.scan_camera_off).assertIsDisplayed()
    assertEquals(CameraPermission.REFUSED, viewModel.uiState.value.permission)
  }
}
