// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

/**
 * The text read on a photo, line by line, with where each line is on the photo. Where matters as
 * much as what: it tells a doctor's name in a letterhead from a medication further down, and puts
 * the cells of a table row back together. Plain Kotlin, so the parser can be tested without Android
 * or ML Kit.
 *
 * @property width width of the upright photo, in pixels, which [TextBox] coordinates refer to
 * @property height height of the upright photo, in pixels
 * @property lines every line read, in no guaranteed order (a two-column page can come back in any
 *   order); empty when no text was found
 */
data class RecognizedText(val width: Int, val height: Int, val lines: List<RecognizedLine>) {
  /** Whether no text at all was found on the photo. */
  fun isEmpty(): Boolean = lines.isEmpty()
}

/**
 * One line of text read on the photo.
 *
 * @property text the line as read, never blank
 * @property box where the line is on the upright photo; `null` if the recognizer didn't say
 * @property confidence how sure the recognizer is about [text], from 0 (unsure) to 1 (sure)
 */
data class RecognizedLine(val text: String, val box: TextBox?, val confidence: Float)

/** A rectangle on the upright photo, in pixels from its top-left corner. */
data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int)
