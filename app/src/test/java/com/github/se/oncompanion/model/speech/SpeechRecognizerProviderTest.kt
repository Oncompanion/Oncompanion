// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.speech

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks which recognizer [SpeechRecognizerProvider] hands out, before and after the app starts.
 */
@RunWith(AndroidJUnit4::class)
class SpeechRecognizerProviderTest {
  private val context: Application = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    SpeechRecognizerProvider.reset()
  }

  @After
  fun tearDown() {
    SpeechRecognizerProvider.reset()
  }

  /** Once the app has started, screens listen with the phone's recognizer, always the same one. */
  @Test
  fun installingGivesThePhonesRecognizerOnce() {
    SpeechRecognizerProvider.install(context)
    val installed = SpeechRecognizerProvider.recognizer

    SpeechRecognizerProvider.install(context)

    assertTrue(installed is AndroidSpeechRecognizer)
    assertSame(installed, SpeechRecognizerProvider.recognizer)
  }

  /** A test's fake set before the app starts is the one screens get, not the phone's recognizer. */
  @Test
  fun aRecognizerSetBeforeInstallingStays() {
    val fake =
        object : SpeechRecognizer {
          override fun isAvailable() = false

          override fun listen(language: SpeechLanguage): Flow<SpeechEvent> = emptyFlow()

          override fun stop() = Unit
        }
    SpeechRecognizerProvider.recognizer = fake

    SpeechRecognizerProvider.install(context)

    assertSame(fake, SpeechRecognizerProvider.recognizer)
  }

  /** Forgetting to install fails clearly, instead of listening with no context. */
  @Test
  fun usingItBeforeInstallingFails() {
    assertThrows(IllegalStateException::class.java) { SpeechRecognizerProvider.recognizer }
  }
}
