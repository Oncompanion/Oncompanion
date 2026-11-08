// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

/**
 * What happens while the app listens, in order: [Listening], any number of [PartialText], then
 * exactly one [FinalText] or [Failed], after which nothing more is sent.
 */
sealed interface SpeechEvent {
  /** The microphone is open: the user can start speaking. */
  data object Listening : SpeechEvent

  /** What has been understood so far, while the user is still speaking. Never blank. */
  data class PartialText(val text: String) : SpeechEvent

  /**
   * Everything the user said, once they stopped. Never blank: silence is [SpeechError.NO_SPEECH].
   */
  data class FinalText(val text: String) : SpeechEvent

  /** Listening stopped without a text, because of [error]. */
  data class Failed(val error: SpeechError) : SpeechEvent
}

/** Why listening failed, grouped by what the user can do about it. */
enum class SpeechError {
  /** Nothing was understood: the user said nothing, or nothing recognizable. They can try again. */
  NO_SPEECH,

  /** The app isn't allowed to use the microphone. */
  NO_PERMISSION,

  /** The phone's recognizer is already listening for another app, or asked too often. */
  BUSY,

  /** The phone can only recognize this language online, and it is offline. */
  NEEDS_CONNECTION,

  /** The phone can't recognize this language, or hasn't downloaded it yet. */
  LANGUAGE_UNAVAILABLE,

  /** The phone has no speech recognizer at all (e.g. no Google services). */
  UNAVAILABLE,

  /** Anything else: the microphone or the recognizer failed. */
  FAILED,
}
