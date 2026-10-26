// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [TextRecognizerProvider] exposes by default and that it can be replaced. */
class TextRecognizerProviderTest {
  private val original = TextRecognizerProvider.recognizer

  @After
  fun restoreProvider() {
    TextRecognizerProvider.recognizer = original
  }

  /** The app reads prescriptions with ML Kit unless told otherwise; building it loads nothing. */
  @Test
  fun exposesMlKitByDefault() {
    assertTrue(original is MlKitTextRecognizer)
  }

  /** The recognizer given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRecognizerItWasGiven() {
    val fake = FakeTextRecognizer()
    TextRecognizerProvider.recognizer = fake
    assertSame(fake, TextRecognizerProvider.recognizer)
  }
}
