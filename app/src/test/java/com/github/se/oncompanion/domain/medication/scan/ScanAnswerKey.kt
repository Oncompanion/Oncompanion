// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.scan.comparable
import java.time.LocalDate

/**
 * What is really written on the fictional test sheets (`src/test/resources/scan/`), shared by the
 * tests of the reading. Sheets 1 to 6 were read while the reading was written; the later ones were
 * made after it, with their answers written before they were read, so they show how it does on
 * prescriptions it was never tuned on.
 */
object ScanAnswerKey {

  /** What is written on a sheet: the doctor, the date, and each medication's details. */
  data class Written(
      val doctor: String,
      val date: LocalDate,
      val medications: List<Medication>,
  )

  /** A medication as the patient would enter it: [days] is `null` when no duration is given. */
  data class Medication(
      val name: String,
      val dosage: String,
      val frequency: String,
      val days: Int?,
  )

  /** The sheets read while the reading was written. */
  val tuned: Map<Int, Written> =
      mapOf(
          1 to
              Written(
                  "Dr. Anna Martin · Oncology",
                  LocalDate.of(2026, 9, 29),
                  listOf(
                      Medication("Ondansetron 8 mg", "1 tablet", "twice a day", 5),
                      Medication("Dexamethasone 4 mg", "1 tablet", "each morning", 3),
                  ),
              ),
          2 to
              Written(
                  "Dre Claire Fontaine",
                  LocalDate.of(2026, 10, 14),
                  listOf(
                      Medication("Tamoxifène 20 mg", "1 comprimé", "1-0-0", 182),
                      Medication("Pantoprazole 40 mg", "1 comprimé", "1-0-0", 30),
                      Medication("Métoclopramide 10 mg", "1 comprimé", "1-1-1 si nausées", 5),
                  ),
              ),
          3 to
              Written(
                  "Dr Marc Dubois",
                  LocalDate.of(2026, 10, 20),
                  listOf(
                      Medication("Ondansétron 8 mg", "1 comprimé", "2x/jour", 5),
                      Medication(
                          "Paracétamol 500 mg",
                          "2 comprimés",
                          "3 fois par jour si douleurs",
                          null,
                      ),
                  ),
              ),
          4 to
              Written(
                  "Dr Paul Keller",
                  LocalDate.of(2026, 11, 3),
                  listOf(
                      Medication("Dexaméthasone 4 mg", "2 cp", "le matin", 3),
                      Medication("Ondansétron 4 mg", "1 cp", "toutes les 12 heures", 5),
                      Medication("Filgrastim 30 MU", "1 injection", "s.c. par jour", 5),
                  ),
              ),
          5 to
              Written(
                  "Dre Léa Morel",
                  LocalDate.of(2026, 11, 5),
                  listOf(
                      Medication(
                          "Lopéramide 2 mg",
                          "1 gélule",
                          "après chaque selle liquide, au maximum 8 par jour",
                          null,
                      ),
                      Medication("Ondansétron 8 mg", "1 comprimé", "matin et soir", 3),
                  ),
              ),
          6 to
              Written(
                  "Dr J. Roux - Oncologie",
                  LocalDate.of(2026, 11, 11),
                  listOf(
                      Medication("Dexaméthasone 4 mg", "1 comprimé", "1-0-0", 3),
                      Medication("Métoclopramide 10 mg", "1 comprimé", "1-1-1", 5),
                      Medication("Paracétamol 1 g", "", "si fièvre", null),
                  ),
              ),
      )

  /**
   * The sheets made after the reading was written, answers written before they were read. `null`:
   * not a prescription (sheet 11, a drink label), nothing must be read.
   */
  val heldOut: Map<Int, Written?> =
      mapOf(
          7 to
              Written(
                  "Dr. med. Thomas Huber",
                  LocalDate.of(2026, 10, 21),
                  listOf(
                      Medication("Ondansetron 8 mg", "1 Tablette", "1-0-1", 5),
                      Medication("Dexamethason 4 mg", "2 Tabletten", "2-0-0", 3),
                      Medication("Macrogol 10 g", "1 Beutel", "täglich bei Verstopfung", null),
                  ),
              ),
          8 to
              Written(
                  "Dr. Priya Shah, MD",
                  LocalDate.of(2026, 10, 27),
                  listOf(
                      Medication("Capecitabine 500 mg", "3 tablets", "bid", 14),
                      Medication("Ondansetron 8 mg", "1 tablet", "every 8 hours as needed", 5),
                      Medication("Lorazepam 1 mg", "1 tablet", "at bedtime", 7),
                  ),
              ),
          9 to
              Written(
                  "Dr Nadia Benali",
                  LocalDate.of(2026, 10, 16),
                  listOf(
                      Medication("Ondansétron 4 mg", "1 cp", "2/j", 3),
                      Medication("Prednisone 20 mg", "2 cp", "le matin", 5),
                      Medication("Bain de bouche bicarbonate 1,4%", "", "3 fois par jour", 10),
                      Medication("Movicol", "1 sachet", "par jour si constipation", null),
                  ),
              ),
          10 to
              Written(
                  "Dr Étienne Favre",
                  LocalDate.of(2026, 10, 22),
                  listOf(
                      Medication("Létrozole 2,5 mg", "1 comprimé", "par jour", null),
                      Medication(
                          "Calcium 500 mg + vitamine D3 400 UI",
                          "1 comprimé",
                          "matin et soir",
                          null,
                      ),
                      Medication(
                          "Ibuprofène 400 mg",
                          "1 comprimé",
                          "3 fois par jour si douleurs",
                          7,
                      ),
                      Medication("Oméprazole 20 mg", "1 gélule", "le matin", 7),
                      Medication("Lidocaïne gel 2 %", "", "2 fois par jour", null),
                  ),
              ),
          11 to null,
      )

  /** Same words, ignoring case, accents and punctuation ("Dr. Martin · Oncology"). */
  fun same(a: String, b: String) = words(a) == words(b)

  private fun words(text: String) = comparable(text).filter { it.isLetterOrDigit() }
}
