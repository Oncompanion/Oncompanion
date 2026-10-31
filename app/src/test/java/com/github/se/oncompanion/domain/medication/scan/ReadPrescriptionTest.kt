// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.domain.medication.scan.MedicationDetail.DOSAGE
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail.DURATION
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail.FREQUENCY
import com.github.se.oncompanion.domain.medication.scan.MedicationDetail.NAME
import com.github.se.oncompanion.domain.medication.scan.ScanAnswerKey.Written
import com.github.se.oncompanion.domain.medication.scan.ScanAnswerKey.same
import com.github.se.oncompanion.model.medication.scan.RecognizedLine
import com.github.se.oncompanion.model.medication.scan.RecognizedText
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.linesOf
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [readPrescription] on the six test sheets, against what is really written on them
 * ([ScanAnswerKey]): every detail must be read right or be to check, since a wrong detail the
 * patient isn't told to check is the one they would trust.
 */
class ReadPrescriptionTest {
  private val today = LocalDate.of(2026, 11, 20)

  private val answers = ScanAnswerKey.tuned

  /** Fails unless every detail of [read] is what is [written], or is to check. */
  private fun assertRightOrToCheck(sheet: Int, written: Written, read: ScannedPrescription) {
    val toCheck = read.toCheck
    fun check(what: String, right: Boolean, field: ScannedField) =
        assertTrue("Sheet $sheet: $what is wrong but not to check", right || field in toCheck)

    check("the doctor", same(written.doctor, read.draft.prescribedBy), ScannedField.PrescribedBy)
    check("the date", written.date == read.draft.prescribedOn, ScannedField.PrescribedOn)
    // A medication missed or made up couldn't be flagged: there must be exactly as many
    assertEquals("Sheet $sheet medications", written.medications.size, read.draft.medications.size)
    written.medications.zip(read.draft.medications).forEachIndexed { i, (expected, actual) ->
      fun field(detail: MedicationDetail) = ScannedField.OfMedication(i, detail)
      check("${expected.name}'s name", same(expected.name, actual.name), field(NAME))
      check("${expected.name}'s dosage", same(expected.dosage, actual.dosage), field(DOSAGE))
      check(
          "${expected.name}'s frequency",
          same(expected.frequency, actual.frequency),
          field(FREQUENCY),
      )
      check("${expected.name}'s duration", expected.days == actual.durationDays, field(DURATION))
    }
  }

  @Test
  fun everyDetailOfEverySheetIsRightOrToCheck() {
    for ((n, written) in answers) {
      val read = readPrescription(sheet(n), today)
      assertNotNull("Sheet $n", read)
      assertRightOrToCheck(n, written, read!!)
    }
  }

  @Test
  fun sheetsReadRightHaveNothingToCheck() {
    // Flagging everything would pass the test above: what was read right must not be flagged
    for (n in listOf(1, 3, 4, 5)) {
      assertEquals(
          "Sheet $n",
          emptySet<ScannedField>(),
          readPrescription(sheet(n), today)!!.toCheck,
      )
    }
  }

  @Test
  fun onTheSwissSheetOnlyWhatWasntWrittenOrWasConvertedIsToCheck() {
    assertEquals(
        setOf(
            ScannedField.OfMedication(0, DOSAGE),
            ScannedField.OfMedication(0, DURATION),
            ScannedField.OfMedication(1, DOSAGE),
            ScannedField.OfMedication(2, NAME),
            ScannedField.OfMedication(2, DOSAGE),
            ScannedField.OfMedication(2, DURATION),
        ),
        readPrescription(sheet(2), today)!!.toCheck,
    )
  }

  @Test
  fun everyMedicationStartsOnThePrescriptionsDate() {
    val draft = readPrescription(sheet(4), today)!!.draft
    assertTrue(draft.medications.all { it.startDate == LocalDate.of(2026, 11, 3) })
    assertTrue(draft.medications.all { it.id == null })
  }

  @Test
  fun aSheetReadRightMakesADraftThatCanBeSaved() {
    for (n in listOf(1, 3, 4, 5)) assertTrue(
        "Sheet $n",
        readPrescription(sheet(n), today)!!.draft.isValid(),
    )
  }

  @Test
  fun theOrderMLKitReturnsLinesInChangesNothing() {
    for (n in 1..6) {
      val text = sheet(n)
      val shuffled = text.copy(lines = text.lines.shuffled(Random(n)))
      assertEquals("Sheet $n", readPrescription(text, today), readPrescription(shuffled, today))
    }
  }

  @Test
  fun aPhotoReadUnsurelyHasEverythingToCheck() {
    for ((n, written) in answers) {
      val text = sheet(n)
      val unsure = text.copy(lines = text.lines.map { it.copy(confidence = it.confidence / 2) })
      val read = readPrescription(unsure, today)!!
      assertRightOrToCheck(n, written, read)
      assertTrue(ScannedField.PrescribedBy in read.toCheck)
      assertTrue(ScannedField.PrescribedOn in read.toCheck)
      read.draft.medications.indices.forEach { i ->
        assertTrue(ScannedField.OfMedication(i, NAME) in read.toCheck)
      }
    }
  }

  @Test
  fun digitsReadAsLettersAreStillReadRightOrToCheck() {
    // ML Kit reading O for 0 and l for 1 in every number of the sheet
    for ((n, written) in answers) {
      val text = sheet(n)
      val misread =
          text.copy(
              lines =
                  text.lines.map { line ->
                    line.copy(
                        text =
                            line.text.replace(Regex("""\d+""")) {
                              it.value.replace('0', 'O').replace('1', 'l')
                            }
                    )
                  }
          )
      val read = readPrescription(misread, today)
      assertNotNull("Sheet $n", read)
      assertRightOrToCheck(n, written, read!!)
    }
  }

  @Test
  fun aDigitMisreadAsALetterInADurationIsToCheck() {
    // "5 days" read as "S days" on sheet 01: the duration can't be read, so it isn't "no end"
    val text = sheet(1)
    val misread =
        text.copy(
            lines =
                text.lines.map {
                  if (it.text == "1 tablet twice a day 5 days")
                      it.copy(text = "1 tablet twice a day S days")
                  else it
                }
        )
    assertRightOrToCheck(1, answers.getValue(1), readPrescription(misread, today)!!)
  }

  @Test
  fun aPhotoWithoutMedicationsIsNothingRead() {
    assertNull(readPrescription(RecognizedText(3000, 4000, emptyList()), today))
    assertNull(readPrescription(linesOf("Dr J. Roux - Oncologie", "Sion, le 11.11.2026"), today))
    assertNull(readPrescription(linesOf("Ordonnance", "Paracétamol si fièvre"), today))
    // A drink label, with shares and amounts per serving (sheet 11, Sprint 3's phone test)
    assertNull(readPrescription(sheet(11), today))
  }

  @Test
  fun aValidityOrCheckUpDateNeverIsThePrescriptionsDate() {
    val prescribed = "Lausanne, le 20.10.2026"
    val medication = "Ondansétron 8 mg 1 comprimé 2 fois par jour pendant 5 jours"
    for (later in
        listOf(
            "Valable jusqu'au 31.01.2027",
            "Prochain contrôle le 05.11.2026",
            "Follow-up: blood count on 03/11/2026",
            "Valid until 31.01.2027",
            "Gültig bis zum 31.01.2027",
        )) {
      for (lines in
          listOf(
              linesOf("Dr Jean Dupont", later, medication, prescribed),
              linesOf("Dr Jean Dupont", prescribed, medication, later),
          )) {
        val read = readPrescription(lines, today)!!
        assertEquals(later, LocalDate.of(2026, 10, 20), read.draft.prescribedOn)
        assertFalse(later, ScannedField.PrescribedOn in read.toCheck)
      }
      // Alone, it isn't taken for the prescription's date either: none was found
      assertFalse(
          later,
          readPrescription(linesOf("Dr Jean Dupont", medication, later), today)!!.dateFound,
      )
    }
  }

  @Test
  fun aNoteInTheListDoesntChangeTheMedicationAboveIt() {
    for (note in
        listOf(
            "- Contrôle sanguin dans 10 jours",
            "*of the reference intake. Shake before use. Once opened, drink within 3 days.",
            // A sentence with a frequency in it (found by the scan accuracy tests)
            "- Merci de revenir au cabinet avec les résultats de la prise de sang 1 fois par semaine",
        )) {
      val read =
          readPrescription(
              linesOf(
                  "Dr Nadia Benali",
                  "Lausanne, le 16.10.2026",
                  "- Prednisone 20 mg cp: 2 cp le matin pendant 5 jours",
                  note,
              ),
              today,
          )!!
      val prednisone = read.draft.medications.single()
      assertEquals(note, "le matin", prednisone.frequency)
      assertEquals(note, emptySet<ScannedField>(), read.toCheck)
    }
    assertNull(
        readPrescription(linesOf("Dr Nadia Benali", "- Contrôle sanguin dans 10 jours"), today)
    )
  }

  @Test
  fun everyListedMedicationWithoutAStrengthIsItsOwn() {
    val read =
        readPrescription(
            linesOf(
                "Dr Nadia Benali",
                "Lausanne, le 16.10.2026",
                "- Movicol : 1 sachet par jour",
                "• Daflon : 2 comprimés le soir",
                "3. Bepanthen crème : 1 application matin et soir",
            ),
            today,
        )!!
    assertEquals(
        listOf("Movicol", "Daflon", "Bepanthen crème"),
        read.draft.medications.map { it.name },
    )
    assertEquals(
        setOf(0, 1, 2).map { ScannedField.OfMedication(it, NAME) }.toSet(),
        read.toCheck.filter { it is ScannedField.OfMedication && it.detail == NAME }.toSet(),
    )
  }

  @Test
  fun aMedicationAloneHasTheDoctorAndDateToCheck() {
    val read = readPrescription(linesOf("Ondansetron 8 mg", "1 tablet twice a day"), today)!!
    assertEquals("", read.draft.prescribedBy)
    assertEquals(today, read.draft.prescribedOn)
    assertFalse(read.dateFound)
    assertTrue(ScannedField.PrescribedBy in read.toCheck)
    assertTrue(ScannedField.PrescribedOn in read.toCheck)
  }

  @Test
  fun aDateReadIsFound_evenWhenItIsToCheck() {
    for (n in 1..6) assertTrue("Sheet $n", readPrescription(sheet(n), today)!!.dateFound)
    val unsure = linesOf("Sion, le 11.11.2026", "Sion, le 12.11.2026", "Ondansetron 8 mg")
    val read = readPrescription(unsure, today)!!
    assertTrue(read.dateFound)
    assertTrue(ScannedField.PrescribedOn in read.toCheck)
  }

  @Test
  fun atMostTwentyMedicationsAreKept_andTheOthersAreCounted() {
    val text = linesOf(*Array(25) { "Medication${'A' + it} 5 mg" })

    val read = readPrescription(text, today)!!

    assertEquals(20, read.draft.medications.size)
    // Dropped without a word, the patient wouldn't know (review of #231)
    assertEquals(5, read.leftOut)
  }

  @Test
  fun nothingIsLeftOutUpToTwentyMedications() {
    val text = linesOf(*Array(20) { "Medication${'A' + it} 5 mg" })
    assertEquals(0, readPrescription(text, today)!!.leftOut)
    assertEquals(0, readPrescription(sheet(1), today)!!.leftOut)
  }

  @Test
  fun aLineWithoutAPositionIsReadToo() {
    val text = RecognizedText(1000, 1000, listOf(RecognizedLine("Ondansetron 8 mg", null, 0.9f)))
    assertEquals(
        "Ondansetron 8 mg",
        readPrescription(text, today)!!.draft.medications.single().name,
    )
  }
}
