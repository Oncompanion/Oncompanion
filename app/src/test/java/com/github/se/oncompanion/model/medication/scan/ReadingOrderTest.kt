// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [readingOrder] puts what ML Kit read back in reading order, on real readings of the sheets. */
class ReadingOrderTest {

  private fun rowsOf(text: RecognizedText): List<List<String>> =
      readingOrder(text).map { row -> row.lines.map { it.text } }

  private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
      RecognizedLine(text, TextBox(left, top, right, bottom), 1f)

  @Test
  fun theCellsOfATableRowArePutBackTogetherInOrder() {
    val rows = rowsOf(sheet(4))
    assertTrue(listOf("Médicament", "Posologie", "Durée") in rows)
    assertTrue(listOf("Dexaméthasone 4 mg cp", "2 cp le matin", "3 jours") in rows)
    assertTrue(
        listOf("Ondansétron 4 mg cp orodispersible", "1 cp toutes les 12 heures", "5 jours") in rows
    )
    assertTrue(listOf("Filgrastim 30 MU seringue", "1 injection s.c. par jour", "5 jours") in rows)
  }

  @Test
  fun rowsGoFromTopToBottom() {
    val texts = rowsOf(sheet(4)).map { it.first() }
    val order =
        listOf("Clinique du Lac (fictive)", "Médicament", "Dexaméthasone 4 mg cp")
            .map(texts::indexOf)
    assertEquals(order.sorted(), order)
    assertTrue(order.none { it < 0 })
  }

  @Test
  fun twoColumnsAtTheSameHeightShareARowLeftColumnFirst() {
    val rows = rowsOf(sheet(5))
    assertTrue(listOf("PRESCRIPTEUR", "PATIENT") in rows)
    assertTrue(listOf("Dre Léa Morel", "Mme Nora Blanc, née le o2.07.1965") in rows)
  }

  @Test
  fun aTiltedPageKeepsItsLinesApart() {
    // Sheet 06 is turned 3°: each medication stays on its own row
    val rows = rowsOf(sheet(6)).map { it.first() }
    val dex = rows.indexOfFirst { it.startsWith("Dexaméthasone") }
    val meto = rows.indexOfFirst { it.startsWith("Métoclopramide") }
    assertTrue(dex >= 0 && meto > dex)
  }

  @Test
  fun linesOfAListStayOneUnderTheOther() {
    val rows = rowsOf(sheet(2))
    assertTrue(listOf("1. Tamoxifène 20 mg, comprimés") in rows)
    assertTrue(listOf("1-0-0, pendant 6 mois") in rows)
    val first = rows.indexOf(listOf("1. Tamoxifène 20 mg, comprimés"))
    assertEquals(listOf("1-0-0, pendant 6 mois"), rows[first + 1])
  }

  @Test
  fun everyLineIsKeptExactlyOnce() {
    for (n in 1..6) {
      val text = sheet(n)
      val ordered = readingOrder(text).flatMap { it.lines }
      assertEquals("Sheet $n", text.lines.sortedBy { it.text }, ordered.sortedBy { it.text })
    }
  }

  @Test
  fun linesWithoutAPositionComeLastInTheOrderRead() {
    val noBox1 = RecognizedLine("first without box", null, 1f)
    val noBox2 = RecognizedLine("second without box", null, 1f)
    val placed = line("Ondansetron 8 mg", 10, 100, 300, 140)
    val text = RecognizedText(1000, 1000, listOf(noBox1, placed, noBox2))

    assertEquals(
        listOf(listOf(placed.text), listOf(noBox1.text), listOf(noBox2.text)),
        rowsOf(text),
    )
  }

  @Test
  fun aLineOverlappingTheOneBeforeItTooMuchStartsANewRow() {
    // Two lines at the same height but on top of each other aren't cells of one row
    val a = line("Ondansetron 8 mg", 100, 100, 500, 140)
    val b = line("1 tablet", 200, 105, 400, 145)
    assertEquals(2, readingOrder(RecognizedText(1000, 1000, listOf(a, b))).size)
  }

  @Test
  fun aTiltedRowIsFollowedCellByCell() {
    // Each cell 15 px lower than the one before: 45 px from first to last, more than a line
    val cells =
        (0..3).map { i ->
          line("cell $i", 100 + 250 * i, 100 + 15 * i, 300 + 250 * i, 140 + 15 * i)
        }
    assertEquals(listOf(cells.map { it.text }), rowsOf(RecognizedText(1200, 400, cells.reversed())))
  }

  @Test
  fun twoTiltedRowsStaySeparate() {
    val first =
        (0..2).map { i -> line("a$i", 100 + 250 * i, 100 + 15 * i, 300 + 250 * i, 140 + 15 * i) }
    val second =
        (0..2).map { i -> line("b$i", 100 + 250 * i, 160 + 15 * i, 300 + 250 * i, 200 + 15 * i) }
    assertEquals(
        listOf(first.map { it.text }, second.map { it.text }),
        rowsOf(RecognizedText(1200, 400, second + first)),
    )
  }

  @Test
  fun aCellSlightlyOverlappingItsLeftNeighborStaysInTheRow() {
    val a = line("Ondansetron 8 mg", 100, 100, 500, 140)
    val b = line("1 tablet", 495, 102, 700, 142)
    assertEquals(listOf(listOf(a.text, b.text)), rowsOf(RecognizedText(1000, 1000, listOf(b, a))))
  }

  @Test
  fun identicalLinesAreBothKept() {
    val text =
        RecognizedText(
            1000,
            1000,
            listOf(line("5 jours", 100, 100, 200, 140), line("5 jours", 100, 300, 200, 340)),
        )
    assertEquals(listOf(listOf("5 jours"), listOf("5 jours")), rowsOf(text))
  }

  @Test
  fun nothingReadGivesNoRows() {
    assertTrue(readingOrder(RecognizedText(1000, 1000, emptyList())).isEmpty())
  }
}
