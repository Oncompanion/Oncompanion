// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.scan.ClassifiedLine
import com.github.se.oncompanion.model.medication.scan.ClassifiedRow
import com.github.se.oncompanion.model.medication.scan.LineKind
import com.github.se.oncompanion.model.medication.scan.RecognizedLine
import com.github.se.oncompanion.model.medication.scan.RecognizedText
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.linesOf
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import com.github.se.oncompanion.model.medication.scan.TextBox
import com.github.se.oncompanion.model.medication.scan.classify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [medicationsIn] on what ML Kit read on the test sheets: every medication with its name, dosage,
 * frequency and duration, and which of them are to check.
 */
class ScannedMedicationsTest {

  private fun medicationsOf(text: RecognizedText) = medicationsIn(classify(text))

  /** A medication whose details are all trusted. */
  private fun sure(name: String, dosage: String, frequency: String, days: Int?) =
      ScannedMedication(
          Reading(name, true),
          Reading(dosage, true),
          Reading(frequency, true),
          Reading(days, true),
      )

  @Test
  fun typedEnglishSheet() {
    assertEquals(
        listOf(
            sure("Ondansetron 8 mg", "1 tablet", "twice a day", 5),
            sure("Dexamethasone 4 mg", "1 tablet", "each morning", 3),
        ),
        medicationsOf(sheet(1)),
    )
  }

  @Test
  fun swissSheetWithoutDosageAMonthDurationAndASplitName() {
    assertEquals(
        listOf(
            // No quantity is written ("1-0-0" alone): never guessed, so the dosage is to check;
            // 6 months counted as 180 days, to check
            ScannedMedication(
                Reading("Tamoxifène 20 mg", true),
                Reading("", false),
                Reading("1-0-0", true),
                Reading(180, false),
            ),
            ScannedMedication(
                Reading("Pantoprazole 40 mg", true),
                Reading("", false),
                Reading("1-0-0", true),
                Reading(30, true),
            ),
            // Read with a space inside the name, and "au maximum" is not an exact duration
            ScannedMedication(
                Reading("Métoclopram ide 10 mg", false),
                Reading("", false),
                Reading("1-1-1 si nausées", true),
                Reading(5, false),
            ),
        ),
        medicationsOf(sheet(2)),
    )
  }

  @Test
  fun writtenOutSheetWithAMedicationWithoutDuration() {
    assertEquals(
        listOf(
            sure("Ondansétron 8 mg", "1 comprimé", "2x/jour", 5),
            sure("Paracétamol 500 mg", "2 comprimés", "3 fois par jour si douleurs", null),
        ),
        medicationsOf(sheet(3)),
    )
  }

  @Test
  fun tableSheetUsesTheCellsOfEachRowAndStopsBeforeTheNote() {
    assertEquals(
        listOf(
            sure("Dexaméthasone 4 mg", "2 cp", "le matin", 3),
            sure("Ondansétron 4 mg", "1 cp", "toutes les 12 heures", 5),
            sure("Filgrastim 30 MU", "1 injection", "s.c. par jour", 5),
        ),
        medicationsOf(sheet(4)),
    )
  }

  @Test
  fun twoDoctorsSheet() {
    assertEquals(
        listOf(
            sure(
                "Lopéramide 2 mg",
                "1 gélule",
                "après chaque selle liquide, au maximum 8 par jour",
                null,
            ),
            sure("Ondansétron 8 mg", "1 comprimé", "matin et soir", 3),
        ),
        medicationsOf(sheet(5)),
    )
  }

  @Test
  fun tiltedSheetSkipsTheStampAndFlagsTheHandwrittenLine() {
    assertEquals(
        listOf(
            ScannedMedication(
                Reading("Dexaméthasone 4 mg", true),
                Reading("", false),
                Reading("1-0-0", true),
                Reading(3, true),
            ),
            ScannedMedication(
                Reading("Métoclopramide 10 mg", true),
                Reading("", false),
                Reading("1-1-1", true),
                Reading(5, true),
            ),
            // Handwritten, read at 0.57: everything read on it is to check
            ScannedMedication(
                Reading("Paracétamot 1 g", false),
                Reading("", false),
                Reading("", false),
                Reading(null, false),
            ),
        ),
        medicationsOf(sheet(6)),
    )
  }

  @Test
  fun weeksAreCountedAsSevenDaysAndToCheck() {
    val medication = medicationsOf(linesOf("Lorazépam 1 mg", "1 cp le soir pendant 2 semaines"))
    assertEquals(Reading<Int?>(14, false), medication.single().durationDays)
    assertEquals(Reading("le soir", true), medication.single().frequency)
  }

  @Test
  fun aDurationAMedicationCantHaveIsDroppedAndToCheck() {
    val medication = medicationsOf(linesOf("Lorazépam 1 mg", "1 cp le soir pendant 300 mois"))
    assertEquals(Reading<Int?>(null, false), medication.single().durationDays)
  }

  @Test
  fun aDurationThatCantBeReadMakesTheFrequencyAndDurationToCheck() {
    // "5" read as "S": no duration is found, but "days" is still there
    val medication =
        medicationsOf(linesOf("Ondansetron 8 mg", "1 tablet twice a day S days")).single()
    assertFalse(medication.frequency.sure)
    assertEquals(Reading<Int?>(null, false), medication.durationDays)
  }

  @Test
  fun aDayInAFrequencyIsNotAnUnreadDuration() {
    for (dosing in
        listOf(
            "1 cp 2x/jour",
            "1 cp par jour",
            "1 tablet twice a day",
            "1 cp chaque jour",
            // Chemotherapy cycles
            "1 injection toutes les 3 semaines",
            "1 injection every 2 weeks",
            "1 perfusion tous les 21 jours",
            "1 injection toutes les 3 sem",
        )) {
      val medication = medicationsOf(linesOf("Ondansetron 8 mg", dosing)).single()
      assertTrue(dosing, medication.frequency.sure)
      assertEquals(dosing, Reading<Int?>(null, true), medication.durationDays)
    }
  }

  @Test
  fun anUnsureMedicationLineMakesEveryDetailToCheck() {
    val text =
        RecognizedText(
            1000,
            400,
            listOf(
                RecognizedLine(
                    "Pantoprazole 40 mg 1 cp le matin 5 jours",
                    TextBox(50, 50, 900, 90),
                    0.5f,
                )
            ),
        )
    val medication = medicationsOf(text).single()
    assertFalse(medication.name.sure)
    assertFalse(medication.dosage.sure)
    assertFalse(medication.frequency.sure)
    assertFalse(medication.durationDays.sure)
  }

  @Test
  fun onlyTheFirstQuantityIsTheDosage() {
    val medication =
        medicationsOf(linesOf("Paracétamol 500 mg", "2 cp le matin, 1 cp le soir")).single()
    assertEquals("2 cp", medication.dosage.value)
    assertEquals("le matin, 1 cp le soir", medication.frequency.value)
  }

  @Test
  fun aNameTooLongIsCutAndToCheck() {
    val name = medicationsOf(linesOf("Medication" + "x".repeat(120) + " 5 mg")).single().name
    assertEquals(100, name.value.length)
    assertFalse(name.sure)
  }

  @Test
  fun severalSpacesBeforeTheUnitAreStillAStrength() {
    // ML Kit can keep two spaces: the classifier and the reader must agree it's a strength (review
    // of #231)
    val medication =
        medicationsOf(linesOf("Ondansetron 8  mg", "1 comprimé 2x/jour pendant 5 jours")).single()
    assertEquals(Reading("Ondansetron 8  mg", true), medication.name)
    assertEquals("1 comprimé", medication.dosage.value)
    assertEquals(Reading<Int?>(5, true), medication.durationDays)
  }

  @Test
  fun aMedicationLineWhoseStrengthIsntFoundIsAWholeNameToCheck() {
    // Should the classifier and the reader ever disagree, the scan goes on instead of crashing
    fun line(text: String, kind: LineKind) =
        ClassifiedLine(RecognizedLine(text, box = null, confidence = 1f), text, kind)
    val rows =
        listOf(
            ClassifiedRow(listOf(line("Ondansetron huit mg", LineKind.MEDICATION))),
            ClassifiedRow(listOf(line("1 comprimé le soir", LineKind.DOSING))),
        )

    val medication = medicationsIn(rows).single()

    assertEquals(Reading("Ondansetron huit mg", false), medication.name)
    assertEquals("1 comprimé", medication.dosage.value)
  }

  @Test
  fun aNumberedMedicationLosesItsNumber() {
    val medication = medicationsOf(linesOf("2) Pantoprazole 40 mg", "1 cp le matin")).single()
    assertEquals("Pantoprazole 40 mg", medication.name.value)
  }

  @Test
  fun dosingBeforeAnyMedicationIsIgnored() {
    assertEquals(1, medicationsOf(linesOf("1 cp le matin", "Pantoprazole 40 mg")).size)
  }

  @Test
  fun aDoctorOrDateLineEndsAMedication() {
    val medications = medicationsOf(linesOf("Pantoprazole 40 mg", "Dr J. Roux", "1 cp le matin"))
    assertEquals(Reading("", false), medications.single().dosage)
  }

  @Test
  fun aSureLineWithAnUnsureDosingLineMakesTheDosingToCheck() {
    val text =
        RecognizedText(
            1000,
            400,
            listOf(
                RecognizedLine("Pantoprazole 40 mg", TextBox(50, 50, 600, 90), 0.95f),
                RecognizedLine("1 cp le matin", TextBox(50, 150, 600, 190), 0.5f),
            ),
        )
    val medication = medicationsOf(text).single()
    assertTrue(medication.name.sure)
    assertFalse(medication.dosage.sure)
    assertFalse(medication.frequency.sure)
  }

  @Test
  fun aShortWordAfterTheStrengthIsNotASplitName() {
    // "cp" comes after the strength: it describes the form, not a piece of the name
    assertTrue(medicationsOf(linesOf("Dexaméthasone 4 mg cp", "2 cp le matin")).single().name.sure)
  }
}
