// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The camera of the scan screen. It's an interface so the screen can be tested without a real
 * camera (Robolectric has none); the app uses [CameraXScanCamera].
 *
 * It stays in the UI layer: the preview has to be drawn inside the screen, so it can't hide behind
 * a device service like the text recognizer. Only its result, the photo file, leaves the screen.
 */
interface ScanCamera {

  /**
   * Turns the camera on for [lifecycleOwner]. Throws if the phone has no camera that can be used.
   */
  suspend fun start(context: Context, lifecycleOwner: LifecycleOwner)

  /** Turns the camera off, when the scan screen is left. */
  fun stop()

  /** Takes a photo into [file]. Throws if it couldn't be taken. */
  suspend fun takePhoto(context: Context, file: File)

  /** What the camera sees. */
  @Composable fun Viewfinder(modifier: Modifier)
}

/** [ScanCamera] with CameraX: a preview and a full-quality photo of the prescription. */
class CameraXScanCamera : ScanCamera {

  private val surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
  private val preview =
      Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest.value = it } }

  // Quality over speed: the text of the prescription must be readable on the photo
  private val imageCapture =
      ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()

  private var provider: ProcessCameraProvider? = null

  override suspend fun start(context: Context, lifecycleOwner: LifecycleOwner) {
    val cameraProvider = ProcessCameraProvider.awaitInstance(context)
    // The back camera faces the prescription; the front one is better than none
    val selector =
        listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
            .firstOrNull { runCatching { cameraProvider.hasCamera(it) }.getOrDefault(false) }
            ?: throw IllegalStateException("This phone has no camera")
    cameraProvider.unbind(preview, imageCapture)
    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
    provider = cameraProvider
  }

  override fun stop() {
    provider?.unbind(preview, imageCapture)
    provider = null
  }

  override suspend fun takePhoto(context: Context, file: File) =
      suspendCancellableCoroutine { continuation ->
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
              override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // CameraX can't be stopped mid-photo: if nobody waits for it any more (the screen
                // was left), the photo is deleted as soon as it's written. If that fails, the next
                // scan's ScanPhotoStore.clear() removes it
                if (continuation.isActive) continuation.resume(Unit)
                else if (!file.delete()) Log.w(TAG, "Couldn't delete a photo taken after closing")
              }

              override fun onError(exception: ImageCaptureException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
              }
            },
        )
      }

  @Composable
  override fun Viewfinder(modifier: Modifier) {
    val request by surfaceRequest.collectAsStateWithLifecycle()
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = modifier) }
  }

  private companion object {
    const val TAG = "ScanCamera"
  }
}
