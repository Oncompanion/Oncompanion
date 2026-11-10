// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * A [message] with its [action] (e.g. Retry) beside it, [gap] apart, as in the Figma's banner and
 * snackbar. When the action would take more than half the width, or leave a word of the message no
 * room, the action goes below the message at the end instead: with a long action or a large font on
 * a narrow phone, both stay readable.
 */
@Composable
internal fun MessageAndAction(
    message: @Composable () -> Unit,
    action: (@Composable () -> Unit)?,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
  Layout(contents = listOf(message, action ?: {}), modifier = modifier) { slots, constraints ->
    val messageSlot = slots[0].single()
    val actionSlot = slots[1].firstOrNull()
    val width = constraints.maxWidth
    val loose = constraints.copy(minWidth = 0, minHeight = 0)
    if (actionSlot == null) {
      val text = messageSlot.measure(loose.copy(maxWidth = width))
      return@Layout layout(width, text.height) { text.place(0, 0) }
    }
    val gapPx = gap.roundToPx()
    val actionWidth = actionSlot.maxIntrinsicWidth(Constraints.Infinity)
    val longestWord = messageSlot.minIntrinsicWidth(Constraints.Infinity)
    val beside = actionWidth <= width / 2 && longestWord + gapPx + actionWidth <= width
    if (beside) {
      // One row: the action at the end, both centered on the taller one
      val button = actionSlot.measure(loose.copy(maxWidth = actionWidth))
      val text = messageSlot.measure(loose.copy(maxWidth = width - gapPx - button.width))
      val height = maxOf(text.height, button.height)
      layout(width, height) {
        text.place(0, (height - text.height) / 2)
        button.place(width - button.width, (height - button.height) / 2)
      }
    } else {
      // Stacked: the message on the whole width, the action below it at the end
      val text = messageSlot.measure(loose.copy(maxWidth = width))
      val button = actionSlot.measure(loose.copy(maxWidth = width))
      layout(width, text.height + button.height) {
        text.place(0, 0)
        button.place(width - button.width, text.height)
      }
    }
  }
}
