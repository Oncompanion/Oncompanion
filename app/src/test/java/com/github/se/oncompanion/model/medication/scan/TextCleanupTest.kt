// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [cleanedOcr] undoes the mistakes ML Kit made on phone photos of the test prescriptions, and only
 * those; [comparable] puts text in the form it is compared in.
 */
class TextCleanupTest {

  private fun assertCleaned(expected: String, read: String) =
      assertEquals("Cleaning \"$read\"", expected, cleanedOcr(read))

  @Test
  fun lettersReadInPlaceOfDigitsInsideANumberAreDigits() {
    // Both read on sheet 05
    assertCleaned("Mme Nora Blanc, née le 02.07.1965", "Mme Nora Blanc, née le o2.07.1965")
    assertCleaned("Dr Hugo Perret, 1700 Fribourg", "Dr Hugo Perret, 170o Fribourg")
    assertCleaned("Pantoprazole 40 mg", "Pantoprazole 4O mg")
    assertCleaned("1 cp", "l cp")
    assertCleaned("10 mg", "1O mg")
    assertCleaned("11 jours", "1l jours")
    assertCleaned("101", "IO1")
    assertCleaned("1-0-1", "l-o-1")
  }

  @Test
  fun aNumberReadAsLettersOnlyIsFixedBeforeAUnitOrInADosing() {
    // Otherwise the medication's strength isn't seen and the medication is missed
    assertCleaned("Métoclopramide 10 mg", "Métoclopramide lO mg")
    assertCleaned("Métoclopramide 10mg", "Métoclopramide lOmg")
    assertCleaned("Paracétamol 1 g", "Paracétamol l g")
    assertCleaned("Filgrastim 30 MU", "Filgrastim 3O MU")
    assertCleaned("1-0-1", "l-O-l")
    assertCleaned("1-1-1-0 si nausées", "l-l-l-O si nausées")
  }

  @Test
  fun lettersBeforeAUnitInsideAWordAreKept() {
    for (text in listOf("Dolo mg", "Paracétamol mg", "Bol-lo-lo")) assertCleaned(
        text,
        text,
    )
  }

  @Test
  fun wordsAreNeverTurnedIntoDigits() {
    for (text in
        listOf(
            "Oncology",
            "Dr. Anna Martin Oncology",
            "I'application oncompanion",
            "Lopéramide 2 mg, 1 gélule",
            "8mg",
            "2x/jour",
            "1-0-0, pendant 6 mois",
            "N° AVS :756.0000.0000.00",
            "5ml",
            "le 1er octobre",
            "Sept 3rd 2026",
            "1 - 0 - 1",
            "1 / 2 comprimé",
        )) {
      assertCleaned(text, text)
    }
  }

  @Test
  fun aBarOrLBeforeADoseFormIsAOne() {
    // Read on sheet 01 when the photo was scaled down
    assertCleaned("1 tablet twice a day", "| tablet twice a day")
    assertCleaned("1 comprimé le soir", "l comprimé le soir")
    assertCleaned("1 gélule", "I gélule")
    assertCleaned("matin et soir: 1 cp", "matin et soir: | cp")
  }

  @Test
  fun aBarOrLThatIsntBeforeADoseFormIsKept() {
    assertCleaned("Dexaméthasone 4 mg cp | 2 cp le matin", "Dexaméthasone 4 mg cp | 2 cp le matin")
    assertCleaned("l'ordonnance", "l'ordonnance")
  }

  @Test
  fun spacesInsideADateAreRemoved() {
    // Read on sheet 06
    assertCleaned("Sion, le 11.11.2026", "Sion, le 11. 11.2026")
    assertCleaned("29/09/2026", "29 / 09 / 2026")
    assertCleaned("le 1.2.26", "le 1 . 2 . 26")
  }

  @Test
  fun aDateWithMixedSeparatorsOrAnyOtherNumberIsLeftAlone() {
    assertCleaned("29.09/2026", "29.09/2026")
    assertCleaned("Lausanne, 03.11.2026", "Lausanne, 03.11.2026")
    assertCleaned("1 cp toutes les 12 heures", "1 cp toutes les 12 heures")
  }

  @Test
  fun comparableTextHasNoAccentsCaseOrExtraSpaces() {
    assertEquals("ondansetron 8 mg", comparable("Ondansétron  8 mg"))
    assertEquals("medecin traitant", comparable("  MÉDECIN TRAITANT "))
    assertEquals("nee le 02.07.1965", comparable("Née le 02.07.1965"))
    assertEquals("geneve, le 14.10.2026", comparable("Genève, le\t14.10.2026"))
    assertEquals("gelule", comparable("gélule"))
  }

  @Test
  fun matchableTextKeepsTheLengthAndPlaceOfEveryCharacter() {
    for (text in listOf("Ondansétron  8 mg", " Lopéramide 2 mg,\t1 gélule", "MÉDECIN", "")) {
      val matched = matchable(text)
      assertEquals(text.length, matched.length)
      assertEquals(comparable(text), comparable(matched))
    }
    assertEquals("ondansetron  8 mg", matchable("Ondansétron  8 mg"))
  }
}
