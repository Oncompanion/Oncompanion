// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import java.util.Locale

/**
 * A language the app can listen to. Only English and French: what is heard is turned into a symptom
 * by rules written for these two languages, so listening in another one would give text the app
 * can't understand.
 *
 * @property tag the BCP 47 tag given to the phone's speech recognizer
 */
enum class SpeechLanguage(val tag: String) {
  ENGLISH("en-US"),
  FRENCH("fr-FR");

  companion object {
    /** French when the phone is set to French (in any country), English otherwise. */
    fun of(locale: Locale): SpeechLanguage = if (locale.language == "fr") FRENCH else ENGLISH
  }
}
