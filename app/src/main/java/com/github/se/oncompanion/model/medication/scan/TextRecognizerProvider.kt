// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

/**
 * Holds the [TextRecognizer] the app uses, so the scan names the interface and never ML Kit. Tests
 * replace [recognizer] with a fake.
 */
object TextRecognizerProvider {
  /** ML Kit on the device by default; the model is only loaded when a photo is read. */
  @Volatile var recognizer: TextRecognizer = MlKitTextRecognizer()
}
