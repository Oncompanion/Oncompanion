// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import android.util.Log
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
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
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
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
   * Turns the camera on for [lifecycleOwner] while the flow is collected, and turns it off when the
   * collection stops (the screen is left). Each session starts with [CameraStatus.STARTING], then
   * says [CameraStatus.READY] once the camera is really open, and [CameraStatus.UNAVAILABLE] if it
   * can't be used: no camera, or a failure while opening or later.
   */
  fun open(context: Context, lifecycleOwner: LifecycleOwner): Flow<CameraStatus>

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

  override fun open(context: Context, lifecycleOwner: LifecycleOwner): Flow<CameraStatus> =
      callbackFlow {
        send(CameraStatus.STARTING)
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        // The back camera faces the prescription; the front one is better than none
        val selector =
            listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { cameraProvider.hasCamera(it) }.getOrDefault(false) }
        if (selector == null) {
          send(CameraStatus.UNAVAILABLE)
          awaitClose {}
          return@callbackFlow
        }
        cameraProvider.unbind(preview, imageCapture)
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        // Binding doesn't mean the camera opened: CameraX says when it opens, or fails to
        val cameraState = camera.cameraInfo.cameraState
        val observer = Observer<CameraState> { trySend(it.toStatus()) }
        cameraState.observeForever(observer)
        awaitClose {
          cameraState.removeObserver(observer)
          cameraProvider.unbind(preview, imageCapture)
        }
      }
      // CameraX and its LiveData only work on the main thread, whoever collects the flow
      .flowOn(ContextCompat.getMainExecutor(context).asCoroutineDispatcher())
      .distinctUntilChanged()

  /**
   * A critical error (camera disabled, broken, Do Not Disturb...) won't go away on its own. A
   * recoverable one (e.g. another app uses the camera) is retried by CameraX: still starting.
   */
  private fun CameraState.toStatus(): CameraStatus =
      when {
        error?.type == CameraState.ErrorType.CRITICAL -> CameraStatus.UNAVAILABLE
        type == CameraState.Type.OPEN -> CameraStatus.READY
        else -> CameraStatus.STARTING
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
