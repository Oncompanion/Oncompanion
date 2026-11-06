// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

/**
 * Holds the [SpeechRecognizer] the app uses, so screens name the interface and never Android's
 * speech APIs. Tests replace [recognizer] with a fake.
 */
object SpeechRecognizerProvider {
  /** The phone's own recognizer by default; nothing is created until the user starts speaking. */
  @Volatile var recognizer: SpeechRecognizer = AndroidSpeechRecognizer()
}
