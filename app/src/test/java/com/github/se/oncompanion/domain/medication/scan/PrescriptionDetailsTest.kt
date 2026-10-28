// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.scan.ClassifiedLine
import com.github.se.oncompanion.model.medication.scan.LineKind
import com.github.se.oncompanion.model.medication.scan.RecognizedLine
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import com.github.se.oncompanion.model.medication.scan.classify
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [prescriberIn] and [dateIn]: on the test sheets, and when they can't be sure. */
class PrescriptionDetailsTest {
  private val today = LocalDate.of(2026, 11, 20)

  private fun linesOf(n: Int) = classify(sheet(n)).flatMap { it.lines }

  private fun line(text: String, kind: LineKind, confidence: Float = 0.9f) =
      ClassifiedLine(RecognizedLine(text, null, confidence), text, kind)

  @Test
  fun theDoctorAndDateOfEachSheetAreFoundAndSure() {
    val expected =
        mapOf(
            1 to ("Dr. Anna Martin Oncology" to LocalDate.of(2026, 9, 29)),
            2 to ("Dre Claire Fontaine" to LocalDate.of(2026, 10, 14)),
            3 to ("Dr Marc Dubois" to LocalDate.of(2026, 10, 20)),
            4 to ("Dr Paul Keller" to LocalDate.of(2026, 11, 3)),
            5 to ("Dre Léa Morel" to LocalDate.of(2026, 11, 5)),
            6 to ("Dr J. Roux - Oncologie" to LocalDate.of(2026, 11, 11)),
        )
    for ((n, details) in expected) {
      assertEquals("Sheet $n", Reading(details.first, sure = true), prescriberIn(linesOf(n)))
      assertEquals("Sheet $n", Reading(details.second, sure = true), dateIn(linesOf(n), today))
    }
  }

  @Test
  fun noDoctorGivesAnEmptyNameToCheck() {
    assertEquals(Reading("", sure = false), prescriberIn(listOf(line("Rx", LineKind.LIST_START))))
  }

  @Test
  fun twoDoctorsOrAnUnsureLineAreToCheck() {
    val two = listOf(line("Dr A. Roux", LineKind.DOCTOR), line("Dr B. Morel", LineKind.DOCTOR))
    assertEquals(Reading("Dr A. Roux", sure = false), prescriberIn(two))
    assertFalse(prescriberIn(listOf(line("Dr A. Roux", LineKind.DOCTOR, confidence = 0.6f))).sure)
  }

  @Test
  fun aLineReadExactlyAsSureAsNeededIsTrusted() {
    assertTrue(prescriberIn(listOf(line("Dr A. Roux", LineKind.DOCTOR, confidence = SURE))).sure)
    assertFalse(prescriberIn(listOf(line("Dr A. Roux", LineKind.DOCTOR, confidence = 0.79f))).sure)
  }

  @Test
  fun aNameThatJustFitsIsKeptWhole() {
    val name = "Dr " + "x".repeat(97)
    assertEquals(Reading(name, sure = true), prescriberIn(listOf(line(name, LineKind.DOCTOR))))
  }

  @Test
  fun aNameTooLongIsCutAndToCheck() {
    val name = "Dr " + "x".repeat(150)
    val read = prescriberIn(listOf(line(name, LineKind.DOCTOR)))
    assertEquals(100, read.value.length)
    assertFalse(read.sure)
  }

  @Test
  fun theFamilyDoctorIsNeverThePrescriber() {
    val lines = listOf(line("Dr Hugo Perret", LineKind.OTHER_DOCTOR))
    assertEquals(Reading("", sure = false), prescriberIn(lines))
  }

  @Test
  fun noDateIsNotFound() {
    assertNull(dateIn(emptyList(), today))
  }

  @Test
  fun theBirthDateIsNeverThePrescriptionsDate() {
    val lines = listOf(line("Née le 02.07.1965", LineKind.PATIENT))
    assertNull(dateIn(lines, today))
  }

  @Test
  fun twoDifferentDatesAreToCheckButTheSameOneTwiceIsnt() {
    val two = listOf(line("le 05.11.2026", LineKind.DATE), line("le 06.11.2026", LineKind.DATE))
    assertEquals(Reading(LocalDate.of(2026, 11, 5), sure = false), dateIn(two, today))
    val same = listOf(line("le 05.11.2026", LineKind.DATE), line("5 novembre 2026", LineKind.DATE))
    assertTrue(dateIn(same, today)!!.sure)
  }

  @Test
  fun aDateInTheFutureOrOverAYearOldIsToCheck() {
    assertFalse(dateIn(listOf(line("le 21.11.2026", LineKind.DATE)), today)!!.sure)
    assertFalse(dateIn(listOf(line("le 19.11.2025", LineKind.DATE)), today)!!.sure)
    assertTrue(dateIn(listOf(line("le 20.11.2025", LineKind.DATE)), today)!!.sure)
    assertTrue(dateIn(listOf(line("le 20.11.2026", LineKind.DATE)), today)!!.sure)
  }

  @Test
  fun aDateOnAnUnsureLineIsToCheck() {
    assertFalse(
        dateIn(listOf(line("le 05.11.2026", LineKind.DATE, confidence = 0.5f)), today)!!.sure
    )
  }
}
