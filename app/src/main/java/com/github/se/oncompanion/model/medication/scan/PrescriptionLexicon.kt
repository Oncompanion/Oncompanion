// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.time.DateTimeException
import java.time.LocalDate

/**
 * The words and patterns of a French or English prescription, in one place so a new format means a
 * new word here, not new logic. Every function takes text in [comparable] form (lowercase, no
 * accents), after [cleanedOcr].
 */
internal object PrescriptionLexicon {

  /** A label at the top of a block, saying whose lines follow. */
  enum class Label {
    PRESCRIBER,
    FAMILY_DOCTOR,
    PATIENT,
  }

  /**
   * Every date written in [text], read day first as in Switzerland (05/11/2026 is 5 November), with
   * a two-digit year in the 2000s. Impossible dates (31.02) are left out.
   */
  fun datesIn(text: String): List<LocalDate> =
      (NUMERIC_DATE.findAll(text).mapNotNull { it.date(day = 1, month = 3, year = 4) } +
              ISO_DATE.findAll(text).mapNotNull { it.date(day = 3, month = 2, year = 1) } +
              DAY_MONTH_YEAR.findAll(text).mapNotNull { it.date(day = 1, month = 2, year = 3) } +
              MONTH_DAY_YEAR.findAll(text).mapNotNull { it.date(day = 2, month = 1, year = 3) })
          .toList()

  /** Whether [text] says a date is when someone was born ("née le", "date de naissance"). */
  fun hasBirthClue(text: String): Boolean = BIRTH.containsMatchIn(text)

  /** Whether [text] names a doctor with their title ("Dr", "Dre", "Docteur", "Prof"). */
  fun hasDoctorTitle(text: String): Boolean = DOCTOR_TITLE.containsMatchIn(text)

  /** Whether [text] is about the patient: "Patient", "Mme", an AVS or insurance number. */
  fun hasPatientClue(text: String): Boolean = PATIENT.containsMatchIn(text)

  /** Whether [text] has a medication's strength ("8 mg", "0,5 mg", "1 g", "30 MU"). */
  fun hasStrength(text: String): Boolean = STRENGTH.containsMatchIn(text)

  /** Whether [text] says how much to take each time ("1 comprimé", "2 cp", "1 injection"). */
  fun hasDoseQuantity(text: String): Boolean = DOSE_QUANTITY.containsMatchIn(text)

  /**
   * Whether [text] says how often ("1-0-1", "2x/jour", "twice a day", "le soir", "si douleurs").
   */
  fun hasFrequency(text: String): Boolean = FREQUENCIES.any { it.containsMatchIn(text) }

  /** Whether [text] says for how long ("pendant 5 jours", "3 days"), but not "dans 10 jours". */
  fun hasDuration(text: String): Boolean = DURATION.containsMatchIn(text)

  /**
   * Whether [text] is the heading that opens the list of medications ("Rp/", "Rx", "Ordonnance").
   */
  fun isListStart(text: String): Boolean = text.trim(*PUNCTUATION) in LIST_STARTS

  /** Whether [text] is a column heading of a table of medications ("Médicament", "Posologie"). */
  fun isTableHeading(text: String): Boolean = text.trim(*PUNCTUATION) in TABLE_HEADINGS

  /** The label [text] is, if it is only a label ("PATIENT", "Médecin traitant :"). */
  fun labelOf(text: String): Label? = LABELS[text.trim(*PUNCTUATION)]

  /** Whether [text] starts by naming the family doctor ("Médecin traitant : Dr Perret"). */
  fun startsWithFamilyDoctor(text: String): Boolean = FAMILY_DOCTOR.containsMatchIn(text)

  /**
   * Whether [text] is a contact detail or an identifier, never part of the prescription itself: a
   * phone number, a street or postcode and town, an AVS or insurance number, the signature line.
   */
  fun isContactOrIdentifier(text: String): Boolean = CONTACTS.any { it.containsMatchIn(text) }

  private fun MatchResult.date(day: Int, month: Int, year: Int): LocalDate? {
    val y = groupValues[year].toInt().let { if (it < 100) 2000 + it else it }
    val m = groupValues[month].toIntOrNull() ?: MONTHS.getValue(groupValues[month])
    if (y !in 1900..2100) return null
    return try {
      LocalDate.of(y, m, groupValues[day].toInt())
    } catch (e: DateTimeException) {
      null
    }
  }

  // The names of each month, January first, in French and English, full and abbreviated
  private val MONTHS: Map<String, Int> =
      listOf(
              "janvier janv jan january",
              "fevrier fevr fev feb february",
              "mars mar march",
              "avril avr apr april",
              "mai may",
              "juin jun june",
              "juillet juil jul july",
              "aout aug august",
              "septembre sept sep september",
              "octobre oct october",
              "novembre nov november",
              "decembre dec december",
          )
          .flatMapIndexed { i, names -> names.split(" ").map { it to i + 1 } }
          .toMap()

  // Longest names first, so "sept" isn't read as "sep" followed by "t"
  private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

  // A full stop may end the sentence after it ("le 14.10.2026."), but not go on with a number
  private val NUMERIC_DATE =
      Regex("""(?<![\d.])(\d{1,2})([./-])(\d{1,2})\2(\d{4}|\d{2})(?!\d|\.\d)""")
  private val ISO_DATE = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
  private val DAY_MONTH_YEAR =
      Regex("""(?<!\d)(\d{1,2})(?:er)?\.?\s+($MONTH)\.?,?\s+(\d{4})(?!\d)""")
  private val MONTH_DAY_YEAR =
      Regex("""\b($MONTH)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""")

  private val BIRTH =
      Regex(
          """\bnee?\s+le\b|\bne\(e\)|date de naissance|\bnaissance\b|date of birth|\bdob\b|\bborn\b"""
      )
  private val DOCTOR_TITLE = Regex("""(?<![a-z])(?:dr|dre|docteur|doctoresse|prof)\.?\s+[a-z]""")
  private val PATIENT =
      Regex(
          """\bpatiente?\b|^(?:mme|mlle|m\.|mr|mrs|ms|monsieur|madame)\s|\bavs\b|\bassurance\b|d'assure"""
      )
  private val FAMILY_DOCTOR = Regex("""^(?:medecin traitant|medecin de famille|family doctor)\b""")

  private const val UNIT = """(?:mg|g|µg|mcg|ug|ml|mu|ui|iu|mmol|%)"""
  private val STRENGTH = Regex("""(?<![\d.,])\d+(?:[.,]\d+)?\s?$UNIT(?![a-z])""")

  private const val FORM =
      """(?:comprimes?|cp|gelules?|capsules?|tablets?|pills?|injections?|seringues?|ampoules?|""" +
          """gouttes?|drops?|sachets?|suppositoires?|patchs?|bouffees?|puffs?|doses?)"""
  // Starting a word, so the end of "prednisone tablets" isn't "one tablets"
  private val DOSE_QUANTITY =
      Regex("""(?<![\p{L}\d.,])(?:\d+(?:[.,]\d+)?|1/2|½|une?|deux|trois|one|two)\s*$FORM\b""")

  private val FREQUENCIES =
      listOf(
          Regex("""(?<![\d-])\d\s*-\s*\d\s*-\s*\d(?:\s*-\s*\d)?(?![\d-])"""),
          Regex("""\b\d+\s*(?:x|fois)\s*/?\s*(?:par\s+)?(?:jour|j|day|d|semaine|week)\b"""),
          Regex("""\b(?:once|twice|three times|\d+ times)\s+(?:a|per)\s+(?:day|week)\b"""),
          // Also days and weeks: chemotherapy cycles ("tous les 21 jours", "toutes les 3 semaines")
          Regex(
              """\b(?:tous les|toutes les|every)\s+\d+\s*""" +
                  """(?:h|heures|hours|jours?|days?|semaines?|sem|weeks?)\b"""
          ),
          Regex("""\b(?:matin|midi|soir|coucher|morning|noon|evening|night|bedtime)\b"""),
          Regex("""\b(?:par jour|a day|per day|daily)\b"""),
          Regex(
              """\bsi (?:nausees|douleurs?|fievre|besoin|anxiete)\b|\bas needed\b|\bapres chaque\b"""
          ),
      )

  // Not when it says when something happens ("dans 10 jours", "in 2 weeks") or how often ("tous
  // les 21 jours"); those words as whole words, so "le matin 3 jours" is a duration
  private val DURATION =
      Regex(
          """(?<!\d)(?<!\bdans )(?<!\bin )(?<!\btous les )(?<!\btoutes les )(?<!\bevery )""" +
              """\d+\s*(?:jours?|j|days?|semaines?|sem|weeks?|mois|months?)\b"""
      )

  private val PUNCTUATION = charArrayOf(' ', '/', ':', '.', '-', '·', '*')
  private val LIST_STARTS = setOf("rp", "rx", "ordonnance", "ordonnance medicale", "prescription")
  private val TABLE_HEADINGS =
      setOf(
          "medicament",
          "medicaments",
          "medication",
          "medicine",
          "posologie",
          "dosage",
          "dose",
          "duree",
          "duration",
          "quantite",
          "quantity",
      )
  private val LABELS =
      mapOf(
          "prescripteur" to Label.PRESCRIBER,
          "medecin prescripteur" to Label.PRESCRIBER,
          "prescriber" to Label.PRESCRIBER,
          "medecin traitant" to Label.FAMILY_DOCTOR,
          "medecin de famille" to Label.FAMILY_DOCTOR,
          "family doctor" to Label.FAMILY_DOCTOR,
          "patient" to Label.PATIENT,
          "patiente" to Label.PATIENT,
      )

  private val CONTACTS =
      listOf(
          Regex(
              """\b(?:tel|telephone|phone|fax|natel|mobile)\b|\+41|\b0\d{2}\s\d{3}\s\d{2}\s\d{2}\b"""
          ),
          Regex(
              // The word boundary goes before an abbreviation's dot: "av. de la gare 5". The number
              // comes before any comma or colon, so "route: oral, 1 tablet" or "à la place de
              // Primpéran, 1 cp" stay part of the prescription
              """\b(?:rue|avenue|av|quai|chemin|ch|route|rte|boulevard|bd|place|street|road)\b""" +
                  """\.?[^,:\d]*\d"""
          ),
          Regex("""(?:^|,\s*)\d{4}\s+(?!$UNIT\b)[a-z]{3,}"""),
          Regex("""\b756[.\s]?\d{4}[.\s]?\d{4}[.\s]?\d{2}\b|\b\d{10,}\b|\bgln\b|\bzsr\b"""),
          Regex("""^signature\b"""),
      )
}
