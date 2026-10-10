// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * [ScanCamera] for tests, where there is no real camera.
 *
 * @param startError thrown by [start], as if the phone had no usable camera
 * @param photoError thrown by [takePhoto], as if the photo couldn't be taken
 */
class FakeScanCamera(
    var startError: Exception? = null,
    var photoError: Exception? = null,
) : ScanCamera {

  var starts = 0
    private set

  var stops = 0
    private set

  /** The files the camera wrote, in order. */
  val photos = mutableListOf<File>()

  override suspend fun start(context: Context, lifecycleOwner: LifecycleOwner) {
    startError?.let { throw it }
    starts++
  }

  override fun stop() {
    stops++
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
