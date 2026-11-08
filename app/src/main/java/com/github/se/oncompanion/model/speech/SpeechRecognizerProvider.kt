// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * Holds the [SpeechRecognizer] the app uses, so screens name the interface and never Android's
 * speech APIs. The phone's recognizer needs the app's context, so [install] sets it once when the
 * app starts; tests set [recognizer] to a fake instead.
 */
object SpeechRecognizerProvider {
  @Volatile private var current: SpeechRecognizer? = null

  /** The recognizer screens listen with; [install] must have been called, or a fake set. */
  var recognizer: SpeechRecognizer
    get() = checkNotNull(current) { "SpeechRecognizerProvider.install() wasn't called" }
    set(value) {
      current = value
    }

  /**
   * Uses the phone's recognizer with [context]'s app context, unless one was already set: a test's
   * fake stays when the app starts. Creating it starts nothing until the user speaks.
   */
  @Synchronized
  fun install(context: Context) {
    if (current == null) current = AndroidSpeechRecognizer(context)
  }

  /** Back to before [install], so each test starts from the state the app starts in. */
  @VisibleForTesting
  internal fun reset() {
    current = null
  }
}
