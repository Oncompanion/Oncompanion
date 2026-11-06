// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [SpeechRecognizerProvider] exposes by default and that it can be replaced. */
class SpeechRecognizerProviderTest {
  private val original = SpeechRecognizerProvider.recognizer

  @After
  fun restoreProvider() {
    SpeechRecognizerProvider.recognizer = original
  }

  /**
   * The app listens with the phone's recognizer unless told otherwise; building it starts nothing.
   */
  @Test
  fun exposesThePhonesRecognizerByDefault() {
    assertTrue(original is AndroidSpeechRecognizer)
  }

  /** The recognizer given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRecognizerItWasGiven() {
    val other =
        object : SpeechRecognizer {
          override fun isAvailable(context: Context) = false

          override fun listen(context: Context, language: SpeechLanguage): Flow<SpeechEvent> =
              emptyFlow()

          override fun stop() = Unit
        }
    SpeechRecognizerProvider.recognizer = other
    assertSame(other, SpeechRecognizerProvider.recognizer)
  }
}
