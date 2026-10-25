// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecognizedText.isEmpty] tells "no text on the photo" apart from text read with low confidence.
 */
class RecognizedTextTest {

  @Test
  fun aPhotoWithoutLinesHasNoText() {
    assertTrue(RecognizedText(width = 800, height = 600, lines = emptyList()).isEmpty())
  }

  @Test
  fun aLineReadWithNoConfidenceIsStillText() {
    val text = RecognizedText(800, 600, listOf(RecognizedLine("Rx", box = null, confidence = 0f)))
    assertFalse(text.isEmpty())
  }
}
