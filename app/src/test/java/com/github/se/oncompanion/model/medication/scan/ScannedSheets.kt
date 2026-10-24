// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

/**
 * What ML Kit read on phone photos of the six fictional US-10 test prescriptions
 * (`src/test/resources/scan/sheet-0N.txt`), so the prescription reading is tested on real
 * recognition output: lines in ML Kit's order, OCR mistakes, the edges of other sheets in the
 * background. Shared by the tests of the reading.
 *
 * Sheets: 01 typed English, 02 Swiss French with "1-0-1", 03 French written out, 04 two-column
 * letterhead and a table, 05 prescriber and family doctor, 06 rotated with a stamp.
 */
object ScannedSheets {

  /** The reading of sheet [number] (1 to 6). */
  fun sheet(number: Int): RecognizedText {
    val name = "scan/sheet-%02d.txt".format(number)
    val content =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "No test sheet $name" }.readText()
    var width = 0
    var height = 0
    val lines = mutableListOf<RecognizedLine>()
    for (row in content.lines().filter { it.isNotBlank() && !it.startsWith("#") }) {
      val fields = row.split("\t")
      if (fields[0] == "size") {
        width = fields[1].toInt()
        height = fields[2].toInt()
      } else {
        val (left, top, right, bottom) = fields[1].split(",").map { it.toInt() }
        lines += RecognizedLine(fields[2], TextBox(left, top, right, bottom), fields[0].toFloat())
      }
    }
    return RecognizedText(width, height, lines)
  }

  /** A text read with full confidence, one line per entry, each on its own row of the page. */
  fun linesOf(vararg texts: String): RecognizedText =
      RecognizedText(
          width = 1000,
          height = 100 * (texts.size + 1),
          lines =
              texts.mapIndexed { i, text ->
                RecognizedLine(text, TextBox(50, 100 * i + 50, 950, 100 * i + 90), 1f)
              },
      )
}
