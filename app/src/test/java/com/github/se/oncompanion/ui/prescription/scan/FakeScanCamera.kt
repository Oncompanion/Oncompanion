// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.LifecycleOwner
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * [ScanCamera] for tests, where there is no real camera.
 *
 * @param startError thrown by [open], as if the camera couldn't be turned on
 * @param photoError thrown by [takePhoto], as if the photo couldn't be taken
 */
class FakeScanCamera(
    var startError: Exception? = null,
    var photoError: Exception? = null,
) : ScanCamera {

  /** How many times the camera was turned on. */
  var starts = 0
    private set

  /** How many times the camera was turned off. */
  var stops = 0
    private set

  /**
   * While set and not completed, the camera stays "starting", as a real camera while it opens.
   * Complete it to open the camera.
   */
  var opening: CompletableDeferred<Unit>? = null

  private val failed = MutableStateFlow(false)

  /** The files the camera wrote, in order. */
  val photos = mutableListOf<File>()

  /** Makes the open camera fail for good, as CameraX reports a critical error. */
  fun fail() {
    failed.value = true
  }

  override fun open(context: Context, lifecycleOwner: LifecycleOwner): Flow<CameraStatus> = flow {
    startError?.let { throw it }
    starts++
    try {
      emit(CameraStatus.STARTING)
      opening?.await()
      emit(CameraStatus.READY)
      failed.first { it }
      emit(CameraStatus.UNAVAILABLE)
      awaitCancellation()
    } finally {
      stops++
    }
  }

  override suspend fun takePhoto(context: Context, file: File) {
    photoError?.let { throw it }
    file.writeText("photo")
    photos += file
  }

  @Composable
  override fun Viewfinder(modifier: Modifier) {
    Box(modifier.testTag(VIEWFINDER))
  }

  companion object {
    /** Test tag of the fake camera preview. */
    const val VIEWFINDER = "fake_scan_viewfinder"
  }
}
