// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale of DESIGN.md, so screens space their content with these steps instead of raw
 * dp. The same in light and dark, so a plain object rather than a theme value.
 *
 * The layout of DESIGN.md in these steps: 16 dp side margins ([lg]), 12 dp between cards ([md]), 24
 * dp between form groups ([xl]), and pinned actions 16 dp from the sides and 24 dp from the bottom.
 */
object Spacing {
  /** 4 dp: between an icon and its text, or a label and its value. */
  val xs = 4.dp
  /** 8 dp: between the chips of a group, or small related items. */
  val sm = 8.dp
  /** 12 dp: between cards, and between list rows' parts. */
  val md = 12.dp
  /** 16 dp: side margins of every screen, and padding inside a list row. */
  val lg = 16.dp
  /** 20 dp: side padding inside a card. */
  val lgPlus = 20.dp
  /** 24 dp: between form groups, and below pinned actions. */
  val xl = 24.dp
  /** 32 dp: between the sections of a screen. */
  val xxl = 32.dp
}
