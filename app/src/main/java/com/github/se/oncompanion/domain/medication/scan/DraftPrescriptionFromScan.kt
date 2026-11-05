// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import android.util.Log
import com.github.se.oncompanion.model.medication.scan.TextRecognizer
import com.github.se.oncompanion.model.medication.scan.TextRecognizerProvider
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

/** What a scan found on its photo. */
sealed interface ScanOutcome {
  /** A prescription was read: the draft the form starts from. */
  data class Read(val prescription: ScannedPrescription) : ScanOutcome

  /** No text could be read: a blank, blurry, dark or handwritten photo, or reading failed. */
  data object NoText : ScanOutcome

  /** Text was read surely, but no medication: the photo isn't of a prescription (a label). */
  data object NotAPrescription : ScanOutcome
}

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
   * What is read on [photo]: the draft, or why there is none, so the patient is told whether to
   * retake it or check it's a prescription. Doesn't delete [photo]: its owner does.
   */
  suspend operator fun invoke(photo: File): ScanOutcome {
    val text =
        try {
          recognizer.recognize(photo)
        } catch (e: IOException) {
          Log.w(TAG, "Couldn't read the photo of the prescription", e)
          return ScanOutcome.NoText
        }
    readPrescription(text, LocalDate.now(clock))?.let {
      return ScanOutcome.Read(it)
    }
    // A handwritten prescription is read unsurely (0.57 on the test photo): it isn't told it's
    // not a prescription, only that it couldn't be read
    val sureLines = text.lines.count { it.confidence >= SURE }
    return if (sureLines >= MIN_SURE_LINES) ScanOutcome.NotAPrescription else ScanOutcome.NoText
  }

  private companion object {
    const val TAG = "DraftPrescriptionFromScan"

    /** Lines read surely for a photo without medications to be of something else, like a label. */
    const val MIN_SURE_LINES = 3
  }
}
