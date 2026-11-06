// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Turns what the user says into text, using the phone's speech recognizer. Behind an interface so
 * screens can be tested with a fake, and so nothing else in the app depends on Android's speech
 * APIs.
 *
 * Methods take the [Context] of the screen that listens, so ViewModels never hold one; only the
 * app's context is kept, never the screen's. The app never keeps what is recorded: only the text
 * comes back.
 */
interface SpeechRecognizer {
  /** Whether the phone has a speech recognizer at all; if not, [listen] always fails. */
  fun isAvailable(context: Context): Boolean

  /**
   * Listens to the user in [language] while the flow is collected, preferring recognition on the
   * phone so it works offline when the phone can. See [SpeechEvent] for what is sent and in which
   * order; the flow completes after [SpeechEvent.FinalText] or [SpeechEvent.Failed].
   *
   * Cancelling the collection stops listening and drops what was said. Only one listening at a
   * time: collect on the main thread.
   */
  fun listen(context: Context, language: SpeechLanguage): Flow<SpeechEvent>

  /**
   * The user is done speaking: stops listening, then [listen]'s flow ends as usual, with
   * [SpeechEvent.FinalText] or, if nothing was understood, [SpeechEvent.Failed]. Does nothing when
   * not listening. Call it on the main thread, like [listen].
   */
  fun stop()
}
