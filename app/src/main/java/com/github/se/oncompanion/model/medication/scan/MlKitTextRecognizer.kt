// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Reads text with ML Kit's Latin text recognition (French and English), whose model is bundled in
 * the app: it works offline from the first scan, and the photo and its text never leave the phone
 * (ML Kit only sends usage metrics to Google).
 *
 * The photo is turned upright from its EXIF orientation, so the boxes of [RecognizedText] are those
 * of the page as the user sees it. It is read at full size up to [MAX_SIDE] pixels: scaling a page
 * down makes ML Kit misread small print ("29/09/2026" read as "29/09r2026", "1 tablet" as "|
 * tablet"). Only larger photos are scaled down, to keep memory reasonable.
 *
 * @param ioDispatcher where the photo is decoded, off the main thread
 */
class MlKitTextRecognizer(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) :
    TextRecognizer {

  override suspend fun recognize(photo: File): RecognizedText {
    val image =
        withContext(ioDispatcher) { uprightBitmap(photo) }
            ?: throw IOException("The photo can't be read as an image")
    // ML Kit refuses images this small; no text could be read on them anyway
    if (image.width < MIN_SIDE || image.height < MIN_SIDE) {
      val size = RecognizedText(image.width, image.height, emptyList())
      image.recycle()
      return size
    }
    // One client per photo: scans are rare, and closing it frees the model's memory
    val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    try {
      val text = client.process(InputImage.fromBitmap(image, 0)).await()
      val lines =
          text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
              // ML Kit can place a line cut by the photo's edge a few pixels outside it
              val box = line.boundingBox?.toTextBox()?.clampedTo(image.width, image.height)
              recognizedLine(line.text, box, line.confidence)
            }
          }
      return RecognizedText(image.width, image.height, lines)
    } finally {
      client.close()
      image.recycle()
    }
  }

  /** [photo] decoded at most [MAX_SIDE] pixels long and turned upright; `null` if not an image. */
  private fun uprightBitmap(photo: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(photo.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options =
        BitmapFactory.Options().apply {
          inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
    val decoded = BitmapFactory.decodeFile(photo.path, options) ?: return null
    val degrees = rotationOf(exifOrientation(photo))
    if (degrees == 0) return decoded
    val rotation = Matrix().apply { postRotate(degrees.toFloat()) }
    val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, rotation, true)
    if (upright !== decoded) decoded.recycle()
    return upright
  }

  /** The orientation the camera wrote in [photo]; "normal" if there is none or it can't be read. */
  private fun exifOrientation(photo: File): Int =
      try {
        ExifInterface(photo.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
      }

  private fun Rect.toTextBox() = TextBox(left, top, right, bottom)

  companion object {
    /**
     * Longest side a photo is read at: a 12-megapixel photo (4032 px) is read at full size, a
     * 50-megapixel one is halved. Bitmaps are outside the app's Java heap since Android 8.
     */
    const val MAX_SIDE = 4096

    /** Smallest side, in pixels, ML Kit accepts. */
    const val MIN_SIDE = 32
  }
}

/**
 * The power of two to divide a [width] × [height] photo by so its longest side is at most [maxSide]
 * (what `BitmapFactory` expects as `inSampleSize`).
 */
internal fun sampleSizeFor(
    width: Int,
    height: Int,
    maxSide: Int = MlKitTextRecognizer.MAX_SIDE,
): Int {
  var sampleSize = 1
  // Rounded up, like BitmapFactory does: 8193 px halved is 4097 px, not 4096
  while ((maxOf(width, height) + sampleSize - 1) / sampleSize > maxSide) sampleSize *= 2
  return sampleSize
}

/**
 * How many degrees clockwise a photo with this EXIF [orientation] must be turned to be upright.
 * Mirrored orientations, which a phone camera doesn't write, are left as they are.
 */
internal fun rotationOf(orientation: Int): Int =
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> 90
      ExifInterface.ORIENTATION_ROTATE_180 -> 180
      ExifInterface.ORIENTATION_ROTATE_270 -> 270
      else -> 0
    }

/**
 * A [RecognizedLine], or `null` for a blank one. Spaces around [text] are removed, and [confidence]
 * is kept between 0 and 1.
 */
internal fun recognizedLine(text: String, box: TextBox?, confidence: Float): RecognizedLine? {
  val trimmed = text.trim()
  if (trimmed.isEmpty()) return null
  return RecognizedLine(trimmed, box, confidence.coerceIn(0f, 1f))
}

/** This box, cut to a [width] × [height] photo: none of its sides is outside the photo. */
internal fun TextBox.clampedTo(width: Int, height: Int): TextBox =
    TextBox(
        left = left.coerceIn(0, width),
        top = top.coerceIn(0, height),
        right = right.coerceIn(0, width),
        bottom = bottom.coerceIn(0, height),
    )
