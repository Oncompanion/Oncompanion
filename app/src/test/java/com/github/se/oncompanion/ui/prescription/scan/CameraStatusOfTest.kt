// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each of CameraX's camera states means for the scan screen. */
class CameraStatusOfTest {

  private fun status(type: CameraState.Type, error: Int? = null) =
      cameraStatusOf(CameraState.create(type, error?.let { CameraState.StateError.create(it) }))

  @Test
  fun openCamera_isReady() {
    assertEquals(CameraStatus.READY, status(CameraState.Type.OPEN))
  }

  @Test
  fun cameraOnItsWay_isStillStarting() {
    for (type in
        listOf(
            CameraState.Type.PENDING_OPEN,
            CameraState.Type.OPENING,
            CameraState.Type.CLOSING,
            CameraState.Type.CLOSED,
        )) {
      assertEquals("$type", CameraStatus.STARTING, status(type))
    }
  }

  @Test
  fun criticalError_isUnavailable_evenOnceOpen() {
    for (error in
        listOf(
            CameraState.ERROR_CAMERA_DISABLED,
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED,
        )) {
      assertEquals("$error", CameraStatus.UNAVAILABLE, status(CameraState.Type.CLOSED, error))
      assertEquals("$error", CameraStatus.UNAVAILABLE, status(CameraState.Type.OPEN, error))
    }
  }

  @Test
  fun recoverableError_isStillStarting_asCameraXRetries() {
    // e.g. another app is using the camera
    for (error in listOf(CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE)) {
      assertEquals("$error", CameraStatus.STARTING, status(CameraState.Type.PENDING_OPEN, error))
    }
  }
}
