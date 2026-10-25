// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import com.github.se.oncompanion.model.medication.scan.PrescriptionLexicon.Label
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words and patterns of [PrescriptionLexicon], on text as it reaches it: cleaned up and in
 * comparable form. Most examples are lines read on the test prescriptions.
 */
class PrescriptionLexiconTest {

  /** [read] as the lexicon gets it. */
  private fun c(read: String) = comparable(cleanedOcr(read))

  private fun assertAll(check: (String) -> Boolean, expected: Boolean, vararg texts: String) {
    for (text in texts) assertEquals("\"$text\"", expected, check(c(text)))
  }

  @Test
  fun datesAreReadDayFirstInEveryCommonFormat() {
    val cases =
        mapOf(
            "Lakeside Clinic (fictional) 29/09/2026" to LocalDate.of(2026, 9, 29),
            "Genève, le 14.10.2026" to LocalDate.of(2026, 10, 14),
            // At the end of a sentence (review of #230)
            "Genève, le 14.10.2026." to LocalDate.of(2026, 10, 14),
            "Fait le 05/11/2026." to LocalDate.of(2026, 11, 5),
            "Date : 05/11/2026" to LocalDate.of(2026, 11, 5),
            "Sion, le 11. 11.2026" to LocalDate.of(2026, 11, 11),
            "le 3-11-2026" to LocalDate.of(2026, 11, 3),
            "29.9.26" to LocalDate.of(2026, 9, 29),
            "2026-09-29" to LocalDate.of(2026, 9, 29),
            "Lausanne, le 20 octobre 2026" to LocalDate.of(2026, 10, 20),
            "le 1er octobre 2026" to LocalDate.of(2026, 10, 1),
            "29 sept. 2026" to LocalDate.of(2026, 9, 29),
            "29 Sep 2026" to LocalDate.of(2026, 9, 29),
            "3 février 2026" to LocalDate.of(2026, 2, 3),
            "15 août 2026" to LocalDate.of(2026, 8, 15),
            "11 DÉCEMBRE 2026" to LocalDate.of(2026, 12, 11),
            "September 29, 2026" to LocalDate.of(2026, 9, 29),
            "Sept 3rd 2026" to LocalDate.of(2026, 9, 3),
        )
    for ((text, date) in cases) {
      assertEquals("\"$text\"", listOf(date), PrescriptionLexicon.datesIn(c(text)))
    }
  }

  @Test
  fun impossibleDatesAndOtherNumbersAreNotDates() {
    for (text in
        listOf(
            "31.02.2026",
            "32/01/2026",
            "15.13.2026",
            "1-0-1",
            "1-0-0, pendant 6 mois",
            "N° AVS :756.0000.0000.00",
            "Tél. 022 000 00 00",
            "Ondansétron 8 mg",
            "1 cp toutes les 12 heures",
            "01.01.1850",
            // A number that goes on after the year
            "1.2.2026.5",
        )) {
      assertEquals("\"$text\"", emptyList<LocalDate>(), PrescriptionLexicon.datesIn(c(text)))
    }
  }

  @Test
  fun leapDaysOnlyExistInLeapYears() {
    assertEquals(listOf(LocalDate.of(2028, 2, 29)), PrescriptionLexicon.datesIn(c("29.02.2028")))
    assertEquals(listOf(LocalDate.of(2000, 2, 29)), PrescriptionLexicon.datesIn(c("29.02.2000")))
    assertEquals(emptyList<LocalDate>(), PrescriptionLexicon.datesIn(c("29.02.2027")))
    assertEquals(emptyList<LocalDate>(), PrescriptionLexicon.datesIn(c("29.02.2100")))
  }

  @Test
  fun yearsAreKeptFrom1900To2100() {
    assertEquals(listOf(LocalDate.of(1900, 1, 1)), PrescriptionLexicon.datesIn(c("01.01.1900")))
    assertEquals(listOf(LocalDate.of(2100, 12, 31)), PrescriptionLexicon.datesIn(c("31.12.2100")))
    assertEquals(emptyList<LocalDate>(), PrescriptionLexicon.datesIn(c("31.12.1899")))
    assertEquals(emptyList<LocalDate>(), PrescriptionLexicon.datesIn(c("01.01.2101")))
  }

  @Test
  fun wordsThatOnlyLookLikeAClueAreNotOne() {
    assertAll(PrescriptionLexicon::hasBirthClue, false, "Anne Lebon", "Osborne", "Dobutamine 5 mg")
    assertAll(PrescriptionLexicon::hasDoctorTitle, false, "Professionnel", "Hydroxyurée 500 mg")
    assertAll(PrescriptionLexicon::hasPatientClue, false, "Comme convenu", "Mardi matin")
    assertAll(PrescriptionLexicon::hasStrength, false, "10 minutes", "2 mois", "10 gouttes")
    assertAll(
        PrescriptionLexicon::hasDoseQuantity,
        false,
        "Dexaméthasone 4 mg cp",
        "20 mg, comprimés",
    )
    assertAll(PrescriptionLexicon::hasFrequency, false, "1-0", "N° AVS :756.0000.0000.00")
    assertAll(PrescriptionLexicon::hasDuration, false, "5 juin 2026")
    assertAll(
        PrescriptionLexicon::isContactOrIdentifier,
        false,
        "Pas de signature requise",
        "123456789",
        "Placebo 5 mg",
    )
  }

  @Test
  fun aLineCanHoldSeveralDates() {
    assertEquals(
        listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
        PrescriptionLexicon.datesIn(c("du 01.10.2026 au 31.10.2026")),
    )
  }

  @Test
  fun birthDatesAreRecognizedByWhatComesBefore() {
    assertAll(
        PrescriptionLexicon::hasBirthClue,
        true,
        "Né le 12.03.1978",
        "Mme Nora Blanc, née le o2.07.1965",
        "Date de naissance : 02.07.1965",
        "DOB 12/03/1978",
        "Born March 12, 1978",
        "Né(e) le 12.03.1978",
    )
    assertAll(
        PrescriptionLexicon::hasBirthClue,
        false,
        "Genève, le 14.10.2026",
        "Date : 05/11/2026",
        "Neuchâtel, le 2.11.2026",
    )
  }

  @Test
  fun doctorsAreRecognizedByTheirTitle() {
    assertAll(
        PrescriptionLexicon::hasDoctorTitle,
        true,
        "Dr. Anna Martin Oncology",
        "Dre Claire Fontaine",
        "Dr J. Roux - Oncologie",
        "Docteur Marc Dubois",
        "Prof. Paul Keller",
    )
    assertAll(
        PrescriptionLexicon::hasDoctorTitle,
        false,
        "André Durand",
        "Médecin adjoint",
        "Ordonnance",
        "Dr",
    )
  }

  @Test
  fun patientDetailsAreRecognized() {
    assertAll(
        PrescriptionLexicon::hasPatientClue,
        true,
        "Patient : Sam Rivera",
        "Mme Nora Blanc, née le 02.07.1965",
        "N° AVS :756.0000.0000.00",
        "Assurance : Caisse fictive SA",
        "N° d'assuré : 80756000000000000000",
    )
    assertAll(PrescriptionLexicon::hasPatientClue, false, "Dre Léa Morel", "Ondansétron 8 mg")
  }

  @Test
  fun strengthsAreRecognizedWithOrWithoutASpace() {
    assertAll(
        PrescriptionLexicon::hasStrength,
        true,
        "Ondansetron 8 mg",
        "Ondansetron 8mg",
        "Paracétamol 1 g si fièvre",
        "Filgrastim 30 MU seringue",
        "Métoclopramide 0,5 mg",
        "Vitamine D 1000 UI",
        "Salbutamol 100 µg",
        "Sirop 5 ml",
    )
    assertAll(
        PrescriptionLexicon::hasStrength,
        false,
        "1 gélule",
        "2 gouttes",
        "3 jours",
        "1 cp toutes les 12 heures",
        "1700 Fribourg",
    )
  }

  @Test
  fun doseQuantitiesAreRecognized() {
    assertAll(
        PrescriptionLexicon::hasDoseQuantity,
        true,
        "1 tablet twice a day",
        "2 cp le matin",
        "1 comprimé 2x/jour",
        "1 gélule après chaque selle liquide",
        "1 injection s.c. par jour",
        "1/2 comprimé le soir",
        "une gélule",
        "| tablet each morning",
    )
    assertAll(PrescriptionLexicon::hasDoseQuantity, false, "Ondansetron 8 mg", "3 jours", "Rx")
    // Drugs ending in "-one" or "-une" before their form aren't a quantity (review of #230)
    assertAll(
        PrescriptionLexicon::hasDoseQuantity,
        false,
        "Prednisone tablets",
        "Oxycodone capsules",
        "Aucun comprimé",
        "Dexaméthasone comprimés",
    )
    assertAll(PrescriptionLexicon::hasDoseQuantity, true, "Prednisone, one tablet", "une gélule")
  }

  @Test
  fun frequenciesAreRecognized() {
    assertAll(
        PrescriptionLexicon::hasFrequency,
        true,
        "1-0-0",
        "1-1-1 si nausées",
        "1-0-1-0",
        "2x/jour",
        "3 fois par jour si douleurs",
        "2 x par jour",
        "twice a day",
        "three times a day",
        "each morning",
        "matin et soir",
        "le soir",
        "toutes les 12 heures",
        "every 8 hours",
        // Chemotherapy cycles (review of #230)
        "1 injection toutes les 3 semaines",
        "every 2 weeks",
        "toutes les 48 heures",
        "toutes les 2 jours",
        "1 injection s.c. par jour",
        "après chaque selle liquide",
        "as needed",
    )
    assertAll(
        PrescriptionLexicon::hasFrequency,
        false,
        "Ondansetron 8 mg",
        "pendant 5 jours",
        "29-09-2026",
        "Dre Claire Fontaine",
    )
  }

  @Test
  fun durationsAreRecognizedButNotAppointmentsInSomeDays() {
    assertAll(
        PrescriptionLexicon::hasDuration,
        true,
        "pendant 5 jours",
        "3 jours",
        "5 days",
        "1-0-0, pendant 6 mois",
        "au maximum 5 jours",
        "for 2 weeks",
        "pendant 3 semaines",
        "10 j",
        // After a word ending in "in" or "dans" (a table row read as one line)
        "2 cp le matin 3 jours",
        "1 tablet each morning 5 days",
        "Gardans 4 jours",
    )
    assertAll(
        PrescriptionLexicon::hasDuration,
        false,
        "Contrôle sanguin dans 10 jours.",
        "Control in 2 weeks",
        // How often, not for how long
        "1 injection toutes les 3 semaines",
        "every 2 weeks",
        "1 cp toutes les 12 heures",
        "Ondansetron 8 mg",
    )
  }

  @Test
  fun headingsAndLabelsAreRecognizedOnlyWhenAlone() {
    assertAll(PrescriptionLexicon::isListStart, true, "Rp/", "Rx", "ORDONNANCE", "Ordonnance :")
    assertAll(PrescriptionLexicon::isListStart, false, "cIMEN Ordonnance", "Rx Ondansetron")
    assertAll(PrescriptionLexicon::isTableHeading, true, "Médicament", "Posologie", "Durée", "Dose")
    assertAll(PrescriptionLexicon::isTableHeading, false, "Médicament adjoint", "Dexaméthasone")
    assertEquals(Label.PRESCRIBER, PrescriptionLexicon.labelOf(c("PRESCRIPTEUR")))
    assertEquals(Label.FAMILY_DOCTOR, PrescriptionLexicon.labelOf(c("MÉDECIN TRAITANT :")))
    assertEquals(Label.PATIENT, PrescriptionLexicon.labelOf(c("PATIENT")))
    assertNull(PrescriptionLexicon.labelOf(c("Patient : Sam Rivera")))
    assertTrue(PrescriptionLexicon.startsWithFamilyDoctor(c("Médecin traitant : Dr Hugo Perret")))
    assertFalse(PrescriptionLexicon.startsWithFamilyDoctor(c("Dr Hugo Perret, médecin traitant")))
  }

  @Test
  fun contactDetailsAndIdentifiersAreRecognized() {
    assertAll(
        PrescriptionLexicon::isContactOrIdentifier,
        true,
        "Rue des Tilleuls 12 1200 Genève Tél. 022 000 00 00",
        "Avenue du Parc 3, 1003 Lausanne (fictif)",
        "Quai du Lac 8",
        "Av. de la Gare 5",
        "Ch. des Vignes 3",
        "Rte de Berne 20",
        "1006 Lausanne",
        "1700 Fribourg",
        "N° AVS :756.0000.0000.00",
        "N° d'assuré : 80756000000000000000",
        "Signature",
        "+41 22 000 00 00",
    )
    assertAll(
        PrescriptionLexicon::isContactOrIdentifier,
        false,
        "Vitamine D 1000 UI",
        "Lausanne, le 20 octobre 2026",
        "Dr Paul Keller",
        "Ondansétron 8 mg :1 comprimé 2x/jour pendant 5 jours",
        "Service d'hémato-oncologie",
        // "route" and "place" in a prescription line (review of #230)
        "Route: oral, 1 tablet twice a day",
        "À la place de Primpéran, 1 cp le matin",
    )
  }

  /** Where [part] is in [text], for comparing with the ranges the lexicon finds. */
  private fun rangeOf(part: String, text: String): IntRange {
    val start = text.indexOf(part)
    return start until start + part.length
  }

  @Test
  fun theFirstStrengthAndDoseQuantityAreFound() {
    val text = c("Ondansétron 8 mg :1 comprimé 2x/jour, 4 mg")
    assertEquals(rangeOf("8 mg", text), PrescriptionLexicon.strengthIn(text))
    assertEquals(rangeOf("1 comprime", text), PrescriptionLexicon.doseQuantityIn(text))
    assertNull(PrescriptionLexicon.strengthIn(c("1 cp le matin")))
    assertNull(PrescriptionLexicon.doseQuantityIn(c("Ondansetron 8 mg")))
  }

  @Test
  fun durationsAreCountedInDaysWithTheWordsBeforeThem() {
    val cases =
        mapOf(
            "1-0-0, pendant 30 jours" to PrescriptionLexicon.Duration(30, true, 0..0),
            "3 jours" to PrescriptionLexicon.Duration(3, true, 0..0),
            "1 tablet twice a day 5 days" to PrescriptionLexicon.Duration(5, true, 0..0),
            "1-0-0, pendant 6 mois" to PrescriptionLexicon.Duration(180, false, 0..0),
            "pendant 2 semaines" to PrescriptionLexicon.Duration(14, false, 0..0),
            "1-1-1 si nausées, au maximum 5 jours" to PrescriptionLexicon.Duration(5, false, 0..0),
            "for 1 week" to PrescriptionLexicon.Duration(7, false, 0..0),
        )
    for ((read, expected) in cases) {
      val duration = PrescriptionLexicon.durationIn(c(read))
      assertEquals("\"$read\"", expected.days, duration?.days)
      assertEquals("\"$read\"", expected.exact, duration?.exact)
    }
  }

  @Test
  fun aDurationsRangeIncludesThePendantOrMaximumBeforeIt() {
    val text = c("1-1-1 si nausées, au maximum 5 jours")
    assertEquals(rangeOf("au maximum 5 jours", text), PrescriptionLexicon.durationIn(text)?.range)
    val other = c("1-0-0, pendant 30 jours")
    assertEquals(rangeOf("pendant 30 jours", other), PrescriptionLexicon.durationIn(other)?.range)
    assertNull(PrescriptionLexicon.durationIn(c("Contrôle sanguin dans 10 jours.")))
  }

  @Test
  fun dosingStartsAtItsFirstQuantityFrequencyOrDuration() {
    assertEquals(
        c(", comprimés 1-0-0").indexOf("1-0-0"),
        PrescriptionLexicon.dosingStartIn(c(", comprimés 1-0-0")),
    )
    assertEquals(
        c(" :1 comprimé 2x/jour").indexOf("1 comprime"),
        PrescriptionLexicon.dosingStartIn(c(" :1 comprimé 2x/jour")),
    )
    assertEquals(
        c(" cp 5 jours").indexOf("5 jours"),
        PrescriptionLexicon.dosingStartIn(c(" cp 5 jours")),
    )
    assertNull(PrescriptionLexicon.dosingStartIn(c(" cp orodispersible")))
  }
}
