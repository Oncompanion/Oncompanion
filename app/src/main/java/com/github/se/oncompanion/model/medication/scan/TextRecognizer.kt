// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.io.File
import java.io.IOException

/**
 * Reads the text on the photo of a prescription, on the device. Behind an interface so the scan can
 * be tested with a fake, and so nothing else in the app depends on ML Kit.
 */
interface TextRecognizer {
  /**
   * Reads the text on [photo], a JPEG taken by the scan. Works offline; the photo and its text
   * never leave the phone. Doesn't delete [photo]: that is up to whoever owns it.
   *
   * @return the lines read; [RecognizedText.isEmpty] if there is no text on the photo
   * @throws IOException if [photo] can't be read as an image
   */
  suspend fun recognize(photo: File): RecognizedText
}
