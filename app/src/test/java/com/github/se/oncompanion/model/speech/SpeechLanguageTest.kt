// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which language the app listens in, from the phone's language. */
class SpeechLanguageTest {

  @Test
  fun frenchInAnyCountryElseEnglish() {
    val cases =
        mapOf(
            Locale.FRANCE to SpeechLanguage.FRENCH,
            Locale.forLanguageTag("fr-CH") to SpeechLanguage.FRENCH,
            Locale.CANADA_FRENCH to SpeechLanguage.FRENCH,
            Locale.FRENCH to SpeechLanguage.FRENCH,
            Locale.US to SpeechLanguage.ENGLISH,
            Locale.UK to SpeechLanguage.ENGLISH,
            // Languages the app can't understand fall back to English
            Locale.forLanguageTag("de-CH") to SpeechLanguage.ENGLISH,
            Locale.ITALY to SpeechLanguage.ENGLISH,
            Locale.ROOT to SpeechLanguage.ENGLISH,
        )
    for ((locale, expected) in cases) {
      assertEquals("$locale", expected, SpeechLanguage.of(locale))
    }
  }
}
