// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.resources.C
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The scan screen with the device's real camera (CameraX), shown on its own, without navigation.
 * The Android emulator has a virtual camera. Skipped on a device without any camera.
 */
@RunWith(AndroidJUnit4::class)
class ScanCameraTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  private val photosOnDisk: List<File>
    get() = File(context.cacheDir, ScanPhotoStore.DIRECTORY).listFiles()?.toList().orEmpty()

  @Before
  fun setUp() {
    assumeTrue(
        "no camera on this device",
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
    )
    // Allowed beforehand, so the test doesn't depend on clicking Android's permission dialog
    InstrumentationRegistry.getInstrumentation()
        .uiAutomation
        .grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    ScanPhotoStore(context.cacheDir).clear()
  }

  private fun waitUntil(condition: () -> Boolean) = compose.waitUntil(TIMEOUT_MS, condition)

  private fun cameraReady() =
      compose
          .onAllNodes(hasTestTag(C.Tag.prescription_scan_capture_button) and isEnabled())
          .fetchSemanticsNodes()
          .isNotEmpty()

  // The permission is granted beforehand, so the "camera off" state means the camera is unavailable
  private fun cameraUnavailable() =
      compose
          .onAllNodesWithTag(C.Tag.prescription_scan_camera_off)
          .fetchSemanticsNodes()
          .isNotEmpty()

  @Test
  fun realPhoto_isShown_deletedOnRetake_andDeletedOnClose() {
    var closed = false
    compose.setContent { ScanScreen(onEnterManually = {}, onBack = { closed = true }) }

    // The real camera turns on (the shutter becomes usable), or the screen says it can't be used
    waitUntil { cameraReady() || cameraUnavailable() }
    // Some emulators, like CI's (no back camera, no window), report a camera that can't be opened
    assumeFalse("the camera can't be turned on on this device", cameraUnavailable())
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()

    // The real photo is shown to be checked: a real JPEG in the scans folder of the cache
    waitUntil {
      compose.onAllNodesWithTag(C.Tag.prescription_scan_photo).fetchSemanticsNodes().isNotEmpty()
    }
    val first = photosOnDisk.single()
    assertArrayEquals(JPEG_START, first.readBytes().copyOf(2))

    // Retake deletes it and turns the camera back on
    compose.onNodeWithTag(C.Tag.prescription_scan_retake_button).performClick()
    waitUntil { cameraReady() }
    assertFalse(first.exists())

    // A second photo, then closing the screen deletes it too
    compose.onNodeWithTag(C.Tag.prescription_scan_capture_button).performClick()
    waitUntil {
      compose.onAllNodesWithTag(C.Tag.prescription_scan_photo).fetchSemanticsNodes().isNotEmpty()
    }
    assertEquals(1, photosOnDisk.size)
    compose.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()
    compose.waitForIdle()

    assertEquals(true, closed)
    assertEquals(emptyList<File>(), photosOnDisk)
  }

  private companion object {
    // An emulator without a GPU (like CI's) takes about 20 s to turn the camera on or take a photo
    const val TIMEOUT_MS = 60_000L

    /** Every JPEG file starts with these two bytes. */
    val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
  }
}
