// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake behaves as its documentation says, since every scan test relies on it. */
class FakeTextRecognizerTest {
  private val photo = File("scan-1.jpg")

  @Test
  fun readsNoTextByDefault() = runTest {
    assertTrue(FakeTextRecognizer().recognize(photo).isEmpty())
  }

  @Test
  fun returnsTheGivenTextAndRecordsThePhotos() = runTest {
    val text = FakeTextRecognizer.textOf("Dr. Anna Martin", "Ondansetron 8 mg")
    val recognizer = FakeTextRecognizer(text)
    val other = File("scan-2.jpg")

    assertSame(text, recognizer.recognize(photo))
    recognizer.recognize(other)

    assertEquals(listOf(photo, other), recognizer.photos)
  }

  @Test
  fun textOfReadsEachLineWithFullConfidence() {
    val lines = FakeTextRecognizer.textOf("Rx", "Ondansetron 8 mg").lines
    assertEquals(listOf("Rx", "Ondansetron 8 mg"), lines.map { it.text })
    assertTrue(lines.all { it.confidence == 1f && it.box == null })
  }

  @Test
  fun throwsTheGivenError() = runTest {
    val recognizer = FakeTextRecognizer().apply { error = IOException("unreadable") }
    val thrown = runCatching { recognizer.recognize(photo) }.exceptionOrNull()
    assertTrue(thrown is IOException)
    assertEquals(listOf(photo), recognizer.photos)
  }

  @Test
  fun waitsWhileReadingUntilReleased() = runTest {
    val reading = CompletableDeferred<Unit>()
    val recognizer = FakeTextRecognizer().apply { this.reading = reading }

    val answer = async { recognizer.recognize(photo) }
    testScheduler.advanceUntilIdle()
    assertFalse(answer.isCompleted)

    reading.complete(Unit)
    assertTrue(answer.await().isEmpty())
  }
}
