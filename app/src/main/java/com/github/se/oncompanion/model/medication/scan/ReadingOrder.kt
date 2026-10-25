// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import kotlin.math.abs
import kotlin.math.min

/**
 * One row of a page: the lines side by side at the same height, from left to right. A table row
 * ("Dexaméthasone 4 mg | 2 cp le matin | 3 jours") or a letterhead with a column on each side are
 * rows of several lines; most rows have one.
 */
internal data class TextRow(val lines: List<RecognizedLine>)

/**
 * The lines of [text] in reading order: rows from top to bottom, each from left to right. ML Kit
 * returns lines in no guaranteed order and splits a table row into its cells; this puts them back.
 * Lines without a position come last, in the order they were read.
 */
internal fun readingOrder(text: RecognizedText): List<TextRow> {
  val (placed, unplaced) = text.lines.partition { it.box != null }
  val rows = mutableListOf<MutableList<RecognizedLine>>()
  // From left to right, each line joins the row of the nearest line just left of it at about the
  // same height. Comparing with that neighbor, not the row's first line, follows a slightly tilted
  // photo across a wide row.
  for (line in placed.sortedBy { it.box!!.left }) {
    val row =
        rows
            .filter { isRightNeighbor(it.last(), line) }
            .minByOrNull { abs(centerOf(it.last()) - centerOf(line)) }
    if (row != null) row += line else rows += mutableListOf(line)
  }
  return rows.sortedBy { row -> row.map(::centerOf).average() }.map { TextRow(it) } +
      unplaced.map { TextRow(listOf(it)) }
}

/**
 * Whether [line] continues the row ending with [left]: it starts after it (a little overlap is
 * allowed) and their middles are less than about half a line apart.
 */
private fun isRightNeighbor(left: RecognizedLine, line: RecognizedLine): Boolean {
  val a = left.box!!
  val b = line.box!!
  val height = min(a.bottom - a.top, b.bottom - b.top)
  return b.left >= a.right - height / 2 &&
      abs(centerOf(left) - centerOf(line)) <= height * SAME_ROW_TOLERANCE
}

private fun centerOf(line: RecognizedLine): Float = (line.box!!.top + line.box.bottom) / 2f

/** How far apart, in line heights, the middles of two lines of the same row can be. */
private const val SAME_ROW_TOLERANCE = 0.6f
