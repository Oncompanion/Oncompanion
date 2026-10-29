// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure parts of [MlKitTextRecognizer]: how much a photo is scaled down, how it is turned
 * upright, and how a line read by ML Kit becomes a [RecognizedLine]. Reading real photos is tested
 * on a device (androidTest).
 */
class MlKitTextRecognizerTest {

  @Test
  fun photosUpToTwelveMegapixelsAreReadAtFullSize() {
    // Scaling a page down makes ML Kit misread small print
    assertEquals(1, sampleSizeFor(width = 4032, height = 3024))
    assertEquals(1, sampleSizeFor(width = 3024, height = 4032))
    assertEquals(1, sampleSizeFor(width = 800, height = 1100))
  }

  @Test
  fun largerPhotosAreDividedUntilTheirLongestSideFits() {
    // 50 megapixels, in either orientation
    assertEquals(2, sampleSizeFor(width = 8160, height = 6120))
    assertEquals(2, sampleSizeFor(width = 6120, height = 8160))
    // 200 megapixels: halving once isn't enough
    assertEquals(4, sampleSizeFor(width = 16320, height = 12240))
  }

  @Test
  fun aPhotoExactlyAtTheLimitIsReadAtFullSizeAndOnePixelMoreIsHalved() {
    assertEquals(1, sampleSizeFor(width = 4096, height = 10))
    assertEquals(2, sampleSizeFor(width = 4097, height = 10))
    assertEquals(2, sampleSizeFor(width = 10, height = 4097))
  }

  @Test
  fun halvingRoundsUpLikeAndroidDoes() {
    // 8193 px halved is decoded 4097 px long, over the limit
    assertEquals(2, sampleSizeFor(width = 8192, height = 10))
    assertEquals(4, sampleSizeFor(width = 8193, height = 10))
  }

  @Test
  fun theLimitCanBeGiven() {
    assertEquals(4, sampleSizeFor(width = 1000, height = 400, maxSide = 250))
    assertEquals(1, sampleSizeFor(width = 500, height = 400, maxSide = 500))
    assertEquals(4, sampleSizeFor(width = 1001, height = 10, maxSide = 500))
  }

  @Test
  fun rotatedPhotosAreTurnedBackClockwise() {
    assertEquals(90, rotationOf(ExifInterface.ORIENTATION_ROTATE_90))
    assertEquals(180, rotationOf(ExifInterface.ORIENTATION_ROTATE_180))
    assertEquals(270, rotationOf(ExifInterface.ORIENTATION_ROTATE_270))
  }

  @Test
  fun uprightUnknownOrMirroredPhotosAreNotTurned() {
    assertEquals(0, rotationOf(ExifInterface.ORIENTATION_NORMAL))
    assertEquals(0, rotationOf(ExifInterface.ORIENTATION_UNDEFINED))
    assertEquals(0, rotationOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL))
    assertEquals(0, rotationOf(ExifInterface.ORIENTATION_TRANSPOSE))
    assertEquals(0, rotationOf(42))
  }

  @Test
  fun aLineKeepsItsTextWithoutSurroundingSpacesAndItsBox() {
    val box = TextBox(left = 10, top = 20, right = 300, bottom = 48)
    assertEquals(
        RecognizedLine("Ondansetron 8 mg", box, 0.93f),
        recognizedLine("  Ondansetron 8 mg ", box, 0.93f),
    )
  }

  @Test
  fun aBoxCutByThePhotosEdgeIsKeptInsideThePhoto() {
    // Read on a phone: a line at the left edge of a 3000 × 4000 photo started at x = -11
    assertEquals(
        TextBox(left = 0, top = 178, right = 75, bottom = 290),
        TextBox(left = -11, top = 178, right = 75, bottom = 290).clampedTo(3000, 4000),
    )
    assertEquals(
        TextBox(left = 1940, top = 0, right = 3000, bottom = 4000),
        TextBox(left = 1940, top = -7, right = 3010, bottom = 4012).clampedTo(3000, 4000),
    )
  }

  @Test
  fun aBoxInsideThePhotoIsUnchanged() {
    val box = TextBox(left = 723, top = 1165, right = 1076, bottom = 1208)
    assertEquals(box, box.clampedTo(3000, 4000))
  }

  @Test
  fun spacesInsideALineAreKept() {
    assertEquals("1 tablet · 5 days", recognizedLine("\t1 tablet · 5 days\n", null, 1f)!!.text)
  }

  @Test
  fun aBlankLineIsDropped() {
    assertNull(recognizedLine("   ", box = null, confidence = 0.9f))
    assertNull(recognizedLine("\t\n ", box = null, confidence = 0.9f))
    assertNull(recognizedLine("", box = null, confidence = 0.9f))
  }

  @Test
  fun confidenceStaysBetweenZeroAndOne() {
    assertEquals(1f, recognizedLine("Rx", null, 1.4f)!!.confidence)
    assertEquals(0f, recognizedLine("Rx", null, -0.2f)!!.confidence)
    assertEquals(0f, recognizedLine("Rx", null, 0f)!!.confidence)
    assertEquals(1f, recognizedLine("Rx", null, 1f)!!.confidence)
  }
}
