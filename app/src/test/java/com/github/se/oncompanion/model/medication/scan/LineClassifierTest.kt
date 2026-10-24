// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import com.github.se.oncompanion.model.medication.scan.LineKind.DATE
import com.github.se.oncompanion.model.medication.scan.LineKind.DOCTOR
import com.github.se.oncompanion.model.medication.scan.LineKind.DOSING
import com.github.se.oncompanion.model.medication.scan.LineKind.LABEL
import com.github.se.oncompanion.model.medication.scan.LineKind.LIST_START
import com.github.se.oncompanion.model.medication.scan.LineKind.MEDICATION
import com.github.se.oncompanion.model.medication.scan.LineKind.NOISE
import com.github.se.oncompanion.model.medication.scan.LineKind.OTHER
import com.github.se.oncompanion.model.medication.scan.LineKind.OTHER_DOCTOR
import com.github.se.oncompanion.model.medication.scan.LineKind.PATIENT
import com.github.se.oncompanion.model.medication.scan.LineKind.TABLE_HEADING
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.linesOf
import com.github.se.oncompanion.model.medication.scan.ScannedSheets.sheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [classify] on what ML Kit read on phone photos of the six test prescriptions: each sheet has a
 * trap (birth date, family doctor, table, background scraps), checked here.
 */
class LineClassifierTest {

  /** What each line of [text] is, by the text ML Kit read. */
  private fun kinds(text: RecognizedText): Map<String, LineKind> =
      classify(text).flatMap { it.lines }.associate { it.line.text to it.kind }

  private fun assertKinds(text: RecognizedText, expected: Map<String, LineKind>) {
    val actual = kinds(text)
    for ((line, kind) in expected) assertEquals("\"$line\"", kind, actual[line])
  }

  @Test
  fun typedEnglishPrescription() {
    assertKinds(
        sheet(1),
        mapOf(
            "Dr. Anna Martin Oncology" to DOCTOR,
            "Lakeside Clinic (fictional) 29/09/2026" to DATE,
            "Rx" to LIST_START,
            "Ondansetron 8 mg" to MEDICATION,
            "1 tablet twice a day 5 days" to DOSING,
            "Dexamethasone 4 mg" to MEDICATION,
            "1 tablet each morning 3 days" to DOSING,
            "Signature" to NOISE,
            // A scrap of another sheet, cut by the top of the photo
            "éée pour tester lap plication oncomppanion Non valable Aucune personne ni" to NOISE,
        ),
    )
  }

  @Test
  fun theBirthDateAndPatientDetailsAreNotThePrescriptionsDate() {
    assertKinds(
        sheet(2),
        mapOf(
            "Dre Claire Fontaine" to DOCTOR,
            "Patient : Sam Rivera" to PATIENT,
            "Né le 12.03.1978" to PATIENT,
            "N° AVS :756.0000.0000.00" to PATIENT,
            "Genève, le 14.10.2026" to DATE,
            "Rue des Tilleuls 12 1200 Genève Tél. 022 000 00 00" to NOISE,
            "Rp/" to LIST_START,
            "1. Tamoxifène 20 mg, comprimés" to MEDICATION,
            "1-0-0, pendant 6 mois" to DOSING,
            "3. Métoclopram ide 10 mg, comprimés" to MEDICATION,
            "1-1-1 si nausées, au maximum 5 jours" to DOSING,
        ),
    )
  }

  @Test
  fun aDateInWordsAndMedicationsWithTheirDosingOnOneLine() {
    assertKinds(
        sheet(3),
        mapOf(
            "Lausanne, le 20 octobre 2026" to DATE,
            "Dr Marc Dubois" to DOCTOR,
            "Avenue du Parc 3, 1003 Lausanne (fictif)" to NOISE,
            "ORDONNANCE" to LIST_START,
            "Ondansétron 8 mg :1 comprimé 2x/jour pendant 5 jours" to MEDICATION,
            "Paracétamol 500 mg: 2 comprimés 3 fois par jour si douleurs" to MEDICATION,
            // Read at the photo's left edge, unsure
            "23186" to NOISE,
        ),
    )
  }

  @Test
  fun aTableOfMedicationsAndALetterheadOnTwoColumns() {
    assertKinds(
        sheet(4),
        mapOf(
            "Lausanne, 03.11.2026" to DATE,
            "Dr Paul Keller" to DOCTOR,
            "Quai du Lac 8" to NOISE,
            "1006 Lausanne" to NOISE,
            "Médicament" to TABLE_HEADING,
            "Posologie" to TABLE_HEADING,
            "Durée" to TABLE_HEADING,
            "Dexaméthasone 4 mg cp" to MEDICATION,
            "2 cp le matin" to DOSING,
            "1 cp toutes les 12 heures" to DOSING,
            "Filgrastim 30 MU seringue" to MEDICATION,
            "1 injection s.c. par jour" to DOSING,
            "Contrôle sanguin dans 10 jours." to OTHER,
        ),
    )
    // "3 jours" and "5 jours" are cells of the table rows
    val durations = classify(sheet(4)).flatMap { it.lines }.filter { it.text.endsWith("jours") }
    assertTrue(durations.isNotEmpty())
    assertTrue(durations.all { it.kind == DOSING })
  }

  @Test
  fun theFamilyDoctorIsNotThePrescriber() {
    assertKinds(
        sheet(5),
        mapOf(
            "PRESCRIPTEUR" to LABEL,
            "PATIENT" to LABEL,
            "MÉDECIN TRAITANT" to LABEL,
            "Dre Léa Morel" to DOCTOR,
            "Dr Hugo Perret, 170o Fribourg" to OTHER_DOCTOR,
            "Mme Nora Blanc, née le o2.07.1965" to PATIENT,
            "Assurance : Caisse fictive SA" to PATIENT,
            "Date : 05/11/2026" to DATE,
            "Lopéramide 2 mg, 1 gélule après chaque selle liquide, au maximum 8 par jour" to
                MEDICATION,
            "Ondansétron 8 mg, 1 comprimé matin et soir, pendant 3 jours" to MEDICATION,
            "SPEC" to NOISE,
            "cliniau" to NOISE,
        ),
    )
  }

  @Test
  fun aTiltedSheetWithAStampAndAHandwrittenLine() {
    assertKinds(
        sheet(6),
        mapOf(
            "Dr J. Roux - Oncologie" to DOCTOR,
            "Sion, le 11. 11.2026" to DATE,
            "Ordonnance" to LIST_START,
            "Dexaméthasone 4 mg 1-0-0 3 jours" to MEDICATION,
            "Métoclopramide 10 mg 1-1-1 5 jours" to MEDICATION,
            // Handwritten, misread, but still a name with a strength
            "Paracétamot 1 g si fière" to MEDICATION,
            "CABINET DES VIGNES" to OTHER,
            "cIMEN Ordonnance" to NOISE,
        ),
    )
  }

  @Test
  fun theCleanedTextIsKeptWithTheLine() {
    val line = classify(sheet(5)).flatMap { it.lines }.first { it.line.text.startsWith("Mme") }
    assertEquals("Mme Nora Blanc, née le 02.07.1965", line.text)
  }

  @Test
  fun noPageNeverTakesSomethingElseForADoctorOrADate() {
    // On every sheet: one prescriber line and one date line, nothing else
    for (n in 1..6) {
      val lines = classify(sheet(n)).flatMap { it.lines }
      assertEquals("Sheet $n doctors", 1, lines.count { it.kind == DOCTOR })
      assertEquals("Sheet $n dates", 1, lines.count { it.kind == DATE })
    }
  }

  @Test
  fun aLabelOnlyAppliesToTheLinesJustBelowItInItsColumn() {
    // Far below the family doctor's label, a doctor's title is the prescriber again
    val text =
        RecognizedText(
            1000,
            2000,
            listOf(
                RecognizedLine("MÉDECIN TRAITANT", TextBox(50, 100, 400, 130), 1f),
                RecognizedLine("Dr Hugo Perret", TextBox(50, 140, 400, 170), 1f),
                RecognizedLine("Dr Léa Morel", TextBox(50, 800, 400, 830), 1f),
                RecognizedLine("Dr Paul Keller", TextBox(600, 140, 950, 170), 1f),
            ),
        )
    assertKinds(
        text,
        mapOf(
            "Dr Hugo Perret" to OTHER_DOCTOR,
            "Dr Léa Morel" to DOCTOR,
            "Dr Paul Keller" to DOCTOR,
        ),
    )
  }

  @Test
  fun aFamilyDoctorNamedInlineIsNotThePrescriber() {
    assertEquals(OTHER_DOCTOR, kinds(linesOf("Médecin traitant : Dr Hugo Perret")).values.single())
  }

  @Test
  fun linesWithoutAPositionAreStillClassified() {
    val text = RecognizedText(1000, 1000, listOf(RecognizedLine("Ondansetron 8 mg", null, 0.3f)))
    assertEquals(MEDICATION, kinds(text).values.single())
  }

  @Test
  fun onlyUnsureLinesAtThePhotosEdgeAreScraps() {
    val text =
        RecognizedText(
            1000,
            1000,
            listOf(
                // Cut by the bottom edge, unsure: a scrap of another sheet
                RecognizedLine("Ordonnance fictive", TextBox(100, 960, 600, 1000), 0.7f),
                // At the left edge but sure: kept
                RecognizedLine("Ondansetron 8 mg", TextBox(0, 300, 400, 340), 0.95f),
                // Unsure but inside the photo: kept
                RecognizedLine("Paracétamot 1 g", TextBox(100, 500, 400, 540), 0.57f),
            ),
        )
    assertKinds(
        text,
        mapOf(
            "Ordonnance fictive" to NOISE,
            "Ondansetron 8 mg" to MEDICATION,
            "Paracétamot 1 g" to MEDICATION,
        ),
    )
  }

  @Test
  fun shortOrNumberOnlyLinesAreNoise() {
    assertKinds(linesOf("x", "12345", "--"), mapOf("x" to NOISE, "12345" to NOISE, "--" to NOISE))
  }
}
