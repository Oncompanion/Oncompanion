// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.io.File
import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [TextRecognizer] shared by the tests.
 *
 * - [recognize] returns [result] (no text by default), or throws [error] when it is set.
 * - [photos] lists every photo it was asked to read, in order.
 * - While [reading] is set, [recognize] waits for it to complete, so a test can check what the app
 *   shows while a photo is being read.
 */
class FakeTextRecognizer(var result: RecognizedText = NO_TEXT) : TextRecognizer {

  /** When non-null, [recognize] throws it. */
  var error: Exception? = null

  /** When non-null, [recognize] waits until it completes before answering. */
  var reading: CompletableDeferred<Unit>? = null

  private val _photos = mutableListOf<File>()

  /** The photos [recognize] was given, in order. */
  val photos: List<File>
    get() = _photos.toList()

  override suspend fun recognize(photo: File): RecognizedText {
    _photos += photo
    reading?.await()
    error?.let { throw it }
    return result
  }

  companion object {
    /** A photo on which no text was found. */
    val NO_TEXT = RecognizedText(width = 1000, height = 1400, lines = emptyList())

    /** A text whose lines are [lines], each read with full confidence and no position. */
    fun textOf(vararg lines: String): RecognizedText =
        RecognizedText(
            width = 1000,
            height = 1400,
            lines = lines.map { RecognizedLine(it, box = null, confidence = 1f) },
        )
  }
}
