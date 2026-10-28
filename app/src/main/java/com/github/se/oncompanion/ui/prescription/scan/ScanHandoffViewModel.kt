// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription.scan

import androidx.lifecycle.ViewModel
import com.github.se.oncompanion.domain.medication.scan.ScannedPrescription
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import java.io.File

/** A prescription the scan read, and the photo it was read on. */
data class ReadScan(val prescription: ScannedPrescription, val photo: File)

/**
 * Hands the prescription the scan read to the form that opens after it, in memory only: screens
 * only pass text routes to each other. Shared by the prescriptions screens.
 *
 * It owns the photo from then on, until the form is saved or closed, so a later version can keep it
 * on the phone instead of deleting it. It is deleted when the user leaves the prescriptions screens
 * too, so it never stays behind.
 */
class ScanHandoffViewModel(private val photoStore: ScanPhotoStore) : ViewModel() {

  private var pending: ReadScan? = null

  /** The prescription the form was opened with, if it was opened after a scan. */
  val scanned: ScannedPrescription?
    get() = pending?.prescription

  /** The scan read [scan]: the form that opens next starts from it. */
  fun hand(scan: ReadScan) {
    // The previous scan's photo, unless it is this one handed again
    pending?.takeIf { it.photo != scan.photo }?.let { photoStore.delete(it.photo) }
    pending = scan
  }

  /** The form is saved or closed: the photo is deleted, and the next form starts empty. */
  fun finish() {
    pending?.let { photoStore.delete(it.photo) }
    pending = null
  }

  // The user left the prescriptions screens, or Android cleared them
  override fun onCleared() {
    finish()
  }
}
