// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.scan

import java.io.File
import java.util.UUID

/**
 * Temporary photos of scanned prescriptions, in a folder of the app's private cache.
 *
 * A prescription is a medical document: its photo only exists until it has been checked and read on
 * the device, and it is never uploaded. The cache is private to the app, never backed up, and
 * Android may clear it when the phone runs out of space.
 *
 * @param cacheDir the app's cache directory (`Context.cacheDir`)
 */
class ScanPhotoStore(cacheDir: File) {

  private val directory = File(cacheDir, DIRECTORY)

  /** A new file for the next photo, not created yet: the camera writes it. */
  fun newPhotoFile(): File {
    directory.mkdirs()
    return File(directory, "$PREFIX${UUID.randomUUID()}$EXTENSION")
  }

  /**
   * Deletes [photo]. Does nothing if it isn't one of this store's photos.
   *
   * @return true if the photo is no longer on the phone, so a failed deletion isn't silent
   */
  fun delete(photo: File): Boolean {
    // Never delete a file outside the scans folder, even if given a wrong path
    if (photo.absoluteFile.parentFile != directory.absoluteFile) return false
    return deleted(photo)
  }

  /**
   * Deletes every photo left by an earlier scan, e.g. if the app was killed before the scan screen
   * was closed, so no prescription photo stays on the phone.
   *
   * @return true if no photo is left
   */
  fun clear(): Boolean {
    // Tries every photo, even after one fails
    return directory.listFiles().orEmpty().filterNot(::deleted).isEmpty()
  }

  /** Deletes [file]; true if it's gone, including when it was already gone. */
  private fun deleted(file: File): Boolean = file.delete() || !file.exists()

  companion object {
    /** Name of the folder in the cache where the photos are kept. */
    const val DIRECTORY = "scans"
    private const val PREFIX = "scan-"
    private const val EXTENSION = ".jpg"
  }
}
