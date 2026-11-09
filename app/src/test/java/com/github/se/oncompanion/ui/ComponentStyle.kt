// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Fails unless this text was laid out in the size and weight of [expected], in [color], as in the
 * Figma. Find the text with `useUnmergedTree = true`: a button merges its label into itself.
 */
fun SemanticsNodeInteraction.assertTextStyle(expected: TextStyle, color: Color) {
  val layouts = mutableListOf<TextLayoutResult>()
  val node = fetchSemanticsNode()
  checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
  val style = layouts.single().layoutInput.style
  val text = layouts.single().layoutInput.text
  assertEquals("$text size", expected.fontSize, style.fontSize)
  assertEquals("$text weight", expected.fontWeight, style.fontWeight)
  assertSameColor("$text color", color, style.color)
}

/** How this text is laid out. Find the text with `useUnmergedTree = true`. */
fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
  val layouts = mutableListOf<TextLayoutResult>()
  checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action)
      .invoke(layouts)
  return layouts.single()
}

/**
 * Fails unless this text breaks its lines only between words: a word cut in two means its row left
 * it too little room to read. Find the text with `useUnmergedTree = true`.
 */
fun SemanticsNodeInteraction.assertNoWordIsCut() {
  val layout = textLayout()
  val text = layout.layoutInput.text.text
  for (line in 0 until layout.lineCount - 1) {
    val end = layout.getLineEnd(line)
    assertTrue("\"$text\" is cut after \"${text.take(end)}\"", text[end - 1].isWhitespace())
  }
}

/**
 * The color drawn at ([x], [y]) from this node's top left corner. The test needs
 * `@GraphicsMode(GraphicsMode.Mode.NATIVE)` to draw real pixels.
 */
fun SemanticsNodeInteraction.colorAt(x: Dp, y: Dp): Color {
  val density = fetchSemanticsNode().layoutInfo.density
  val pixels = captureToImage().toPixelMap()
  return with(density) { pixels[x.roundToPx(), y.roundToPx()] }
}

/** Fails unless [actual] is [expected], give or take the rounding of an 8-bit pixel. */
fun assertSameColor(message: String, expected: Color, actual: Color) {
  val channels = listOf(expected.red to actual.red, expected.green to actual.green)
  val close = (channels + (expected.blue to actual.blue)).all { (e, a) -> abs(e - a) <= 3 / 255f }
  assertTrue("$message: expected $expected, was $actual", close)
}
