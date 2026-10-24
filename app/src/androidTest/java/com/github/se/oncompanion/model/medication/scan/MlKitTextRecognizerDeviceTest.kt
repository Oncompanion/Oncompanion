// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MlKitTextRecognizer] reading real photos with ML Kit's bundled model, on the device and offline:
 * upright and sideways photos, blank and tiny ones, photos larger than
 * [MlKitTextRecognizer.MAX_SIDE], and files that aren't images. The prescription in the test assets
 * is fictional (sheet 01 of the US-10 test prescriptions, 1588 × 2246).
 */
@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerDeviceTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
  private val folder = File(context.cacheDir, "recognizer-test").apply { mkdirs() }
  private val recognizer = MlKitTextRecognizer()

  @After
  fun deletePhotos() {
    folder.deleteRecursively()
  }

  @Test
  fun readsTheLinesOfATypedPrescriptionInOrder() =
      runBlocking<Unit> {
        val text = recognizer.recognize(jpeg(prescription(), "upright.jpg"))

        // At most 4096 px long, so read at full size
        assertEquals(1588, text.width)
        assertEquals(2246, text.height)
        val lines = text.lines.map { it.text }
        assertTrue("Lines read: $lines", lines.any { "Ondansetron 8 mg" in it })
        assertTrue("Lines read: $lines", lines.any { "Dexamethasone 4 mg" in it })
        assertTrue("Lines read: $lines", lines.any { "29/09/2026" in it })
        assertTrue("Lines read: $lines", lines.any { "twice a day" in it })
        assertReadTopToBottom(text, "Martin", "Ondansetron", "Dexamethasone", "Signature")
        assertWellFormed(text)
      }

  @Test
  fun aPhotoTakenSidewaysOrUpsideDownIsReadUpright() =
      runBlocking<Unit> {
        for (orientation in
            listOf(
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_180,
                ExifInterface.ORIENTATION_ROTATE_270,
            )) {
          val text =
              recognizer.recognize(jpeg(prescription(), "turned-$orientation.jpg", orientation))

          assertEquals("Orientation $orientation", 1588, text.width)
          assertEquals("Orientation $orientation", 2246, text.height)
          assertReadTopToBottom(text, "Martin", "Ondansetron", "Dexamethasone", "Signature")
          assertWellFormed(text)
        }
      }

  @Test
  fun aSidewaysPhotoGivesTheBoxesOfTheUprightPage() =
      runBlocking<Unit> {
        val upright = recognizer.recognize(jpeg(prescription(), "upright.jpg"))
        val sideways =
            recognizer.recognize(
                jpeg(prescription(), "sideways.jpg", ExifInterface.ORIENTATION_ROTATE_90)
            )

        val expected = upright.lineWith("Ondansetron").box!!
        val actual = sideways.lineWith("Ondansetron").box!!
        // The same place, give or take JPEG noise
        assertTrue("$actual vs $expected", abs(actual.left - expected.left) < 30)
        assertTrue("$actual vs $expected", abs(actual.top - expected.top) < 30)
      }

  @Test
  fun aPngIsReadToo() =
      runBlocking<Unit> {
        val png = File(folder, "page.png")
        png.outputStream().use { prescription().compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertReadTopToBottom(recognizer.recognize(png), "Martin", "Ondansetron", "Dexamethasone")
      }

  @Test
  fun aBlankPageHasNoTextButKeepsItsUprightSize() =
      runBlocking<Unit> {
        val blank = recognizer.recognize(jpeg(page(800, 1000), "blank.jpg"))
        assertTrue(blank.isEmpty())
        assertEquals(800, blank.width)
        assertEquals(1000, blank.height)

        val sideways =
            recognizer.recognize(
                jpeg(page(600, 900), "blank-sideways.jpg", ExifInterface.ORIENTATION_ROTATE_90)
            )
        assertTrue(sideways.isEmpty())
        assertEquals(600, sideways.width)
        assertEquals(900, sideways.height)
      }

  @Test
  fun anImageTooSmallForMlKitHasNoText() =
      runBlocking<Unit> {
        // ML Kit refuses images under 32 px; the recognizer answers "no text" instead of failing
        val text = recognizer.recognize(jpeg(page(1, 1), "tiny.jpg"))
        assertTrue(text.isEmpty())
        assertEquals(1, text.width)
        assertEquals(1, text.height)
      }

  @Test
  fun aPhotoExactlyAtTheLimitIsReadAtFullSize() =
      runBlocking<Unit> {
        val side = MlKitTextRecognizer.MAX_SIDE
        val text =
            recognizer.recognize(
                jpeg(page(side, 1024, "ONDANSETRON 8 MG", textSize = 140f), "max.jpg")
            )

        assertEquals(side, text.width)
        assertEquals(1024, text.height)
        text.lineWith("ONDANSETRON")
      }

  @Test
  fun largerPhotosAreScaledDownUntilTheyFit() =
      runBlocking<Unit> {
        val halved =
            recognizer.recognize(
                jpeg(page(4100, 2000, "ONDANSETRON 8 MG", textSize = 200f), "large.jpg")
            )
        assertEquals(2050, halved.width)
        assertEquals(1000, halved.height)
        halved.lineWith("ONDANSETRON")

        val quartered =
            recognizer.recognize(
                jpeg(page(8200, 1200, "DEXAMETHASONE 4 MG", textSize = 300f), "huge.jpg")
            )
        assertEquals(2050, quartered.width)
        assertEquals(300, quartered.height)
        quartered.lineWith("DEXAMETHASONE")
      }

  @Test
  fun aLargeSidewaysPhotoIsScaledDownAndTurnedUpright() =
      runBlocking<Unit> {
        val text =
            recognizer.recognize(
                jpeg(
                    page(2000, 4100, "ONDANSETRON 8 MG", "TWICE A DAY", textSize = 160f),
                    "large-sideways.jpg",
                    ExifInterface.ORIENTATION_ROTATE_90,
                )
            )

        assertEquals(1000, text.width)
        assertEquals(2050, text.height)
        assertReadTopToBottom(text, "ONDANSETRON", "TWICE")
      }

  @Test
  fun thePhotoIsKeptUnchanged() =
      runBlocking<Unit> {
        val photo = jpeg(prescription(), "kept.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        val before = photo.readBytes()

        recognizer.recognize(photo)

        // Deleting the photo is up to its owner
        assertTrue(photo.exists())
        assertArrayEquals(before, photo.readBytes())
      }

  @Test
  fun filesThatAreNotImagesAreRefused() =
      runBlocking<Unit> {
        val notImages =
            listOf(
                File(folder, "notes.jpg").apply { writeText("not a photo") },
                File(folder, "empty.jpg").apply { createNewFile() },
                File(folder, "missing.jpg"),
                // A JPEG that stops right after its header
                File(folder, "cut.jpg").apply {
                  writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 1, 2))
                },
            )
        for (file in notImages) {
          try {
            recognizer.recognize(file)
            fail("${file.name} isn't an image and must be refused")
          } catch (e: IOException) {
            // Expected
          }
        }
        assertTrue(File(folder, "notes.jpg").exists())
      }

  private fun prescription(): Bitmap =
      testAssets.open("scan/typed-prescription.png").use { BitmapFactory.decodeStream(it) }

  /** A white page of [width] × [height] with [lines] written one under the other. */
  private fun page(width: Int, height: Int, vararg lines: String, textSize: Float = 90f): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.BLACK
          this.textSize = textSize
          typeface = Typeface.DEFAULT_BOLD
        }
    lines.forEachIndexed { i, line ->
      canvas.drawText(line, textSize, textSize * 2 + i * textSize * 2.5f, paint)
    }
    return bitmap
  }

  /**
   * Saves [upright] as a JPEG the way a camera does with this EXIF [orientation]: the pixels are
   * stored turned, and the orientation says how to turn them back.
   */
  private fun jpeg(
      upright: Bitmap,
      name: String,
      orientation: Int = ExifInterface.ORIENTATION_NORMAL,
  ): File {
    val degrees =
        when (orientation) {
          ExifInterface.ORIENTATION_ROTATE_90 -> -90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 90f
          else -> 0f
        }
    val stored =
        if (degrees == 0f) upright
        else
            Bitmap.createBitmap(
                upright,
                0,
                0,
                upright.width,
                upright.height,
                Matrix().apply { postRotate(degrees) },
                true,
            )
    val file = File(folder, name)
    file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    if (orientation != ExifInterface.ORIENTATION_NORMAL) {
      ExifInterface(file.path).apply {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        saveAttributes()
      }
    }
    return file
  }

  private fun RecognizedText.lineWith(word: String): RecognizedLine =
      lines.firstOrNull { word in it.text }
          ?: error("No line with $word in ${lines.map { it.text }}")

  /** Each of [words] is on a line below the previous one. */
  private fun assertReadTopToBottom(text: RecognizedText, vararg words: String) {
    val tops = words.map { text.lineWith(it).box!!.top }
    assertEquals("Tops of ${words.toList()}: $tops", tops.sorted(), tops)
  }

  /** Every line is trimmed, sure between 0 and 1, and inside the photo. */
  private fun assertWellFormed(text: RecognizedText) {
    for (line in text.lines) {
      assertEquals(line.text.trim(), line.text)
      assertTrue(line.confidence in 0f..1f)
      val box = line.box ?: continue
      assertTrue("$box outside the photo", box.left >= 0 && box.top >= 0)
      assertTrue("$box outside the photo", box.right <= text.width && box.bottom <= text.height)
    }
  }
}
