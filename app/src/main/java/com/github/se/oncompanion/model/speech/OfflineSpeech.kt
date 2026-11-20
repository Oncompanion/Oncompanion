// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

/**
 * Whether the phone can understand a language without a connection (US-45), so the app can offer to
 * download it while the patient is still online, before they need it in a waiting room.
 */
enum class OfflineSpeech {
  /** The language is on the phone: listening works without a connection. */
  READY,

  /** The phone can download the language to understand it without a connection. */
  DOWNLOADABLE,

  /** The phone is downloading the language; until then, listening needs a connection. */
  DOWNLOADING,

  /** The phone can only understand this language online. */
  ONLINE_ONLY,

  /** The phone can't tell (Android 12 and older): listening may or may not need a connection. */
  UNKNOWN,
}
