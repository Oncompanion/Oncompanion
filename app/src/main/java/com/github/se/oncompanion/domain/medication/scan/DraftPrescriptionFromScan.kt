// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import android.util.Log
import com.github.se.oncompanion.model.medication.scan.TextRecognizer
import com.github.se.oncompanion.model.medication.scan.TextRecognizerProvider
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/**
 * Reads the prescription on the photo of a scan (US-10): its text on the device, then the draft the
 * form starts from, with the details to check (see [readPrescription]). Nothing is saved: the
 * patient confirms the draft in the form.
 *
 * @param recognizer reads the photo's text; ML Kit by default
 * @param clock today's date, used when no date is read
 */
class DraftPrescriptionFromScan(
    private val recognizer: TextRecognizer = TextRecognizerProvider.recognizer,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
  /**
   * The draft read on [photo]. Doesn't delete [photo]: its owner does.
   *
   * @return `null` if nothing could be read, or if reading failed: either way, the patient enters
   *   the prescription by hand or retakes the photo
   */
  suspend operator fun invoke(photo: File): ScannedPrescription? {
    val text =
        try {
          recognizer.recognize(photo)
        } catch (e: IOException) {
          Log.w(TAG, "Couldn't read the photo of the prescription", e)
          return null
        }
    return readPrescription(text, LocalDate.now(clock))
  }

  private companion object {
    const val TAG = "DraftPrescriptionFromScan"
  }
}
