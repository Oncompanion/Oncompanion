// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.scan.comparable

/**
 * How many doses a day a medication's frequency says, so the patient can tick them on the Overview
 * (US-28). The frequency is free text, typed by the patient or kept as read from a scanned
 * prescription, so English, French and German wordings and the Swiss scheme are read.
 *
 * It only counts what the text says plainly: anything taken only if needed, less often than daily
 * (weekly, every few days, a cycle) or not understood has no count, since a wrong number of doses
 * would be worse than none. The scanner's word lists say whether a text is a frequency at all; this
 * answers another question, how many a day, and shares their text cleanup ([comparable]).
 */
object DoseCount {

  /** Doses a day are counted up to this, one an hour; a larger number is a misreading. */
  const val MAX_PER_DAY = 24

  /** The number of doses a day [frequency] says, or `null` if it doesn't say one plainly. */
  fun of(frequency: String?): Int? {
    if (frequency.isNullOrBlank()) return null
    val text = comparable(frequency)
    if (
        IF_NEEDED.any { it.containsMatchIn(text) } ||
            LESS_THAN_DAILY.any { it.containsMatchIn(text) }
    ) {
      return null
    }
    // The most explicit wording first: a scheme or a number beats the times of day named after it
    val count = readers.firstNotNullOfOrNull { read -> read(text) }
    return count?.takeIf { it in 1..MAX_PER_DAY }
  }

  /** "1-0-1", "1-1-1-1": one dose at each time of the day whose number isn't 0. */
  private fun schemeCount(text: String): Int? =
      SCHEME.find(text)
          ?.groupValues
          ?.drop(1)
          ?.count { it.isNotEmpty() && it != "0" }
          ?.takeIf { it > 0 }

  /** "3x/jour", "2 fois par jour", "3 times a day", "2x taglich", "2/j", "x 2/j". */
  private fun timesADay(text: String): Int? = TIMES_A_DAY.firstNotNullOfOrNull {
    it.find(text)?.groupValues?.get(1)?.toIntOrNull()
  }

  /** "twice a day", "deux fois par jour", "zweimal taglich", and "bid", "tid", "qid", "qd". */
  private fun wordCount(text: String): Int? =
      WORD_COUNTS.entries.firstOrNull { (words, _) -> words.containsMatchIn(text) }?.value

  /** "q8h", "toutes les 8 heures", "every 12 hours", "alle 8 Stunden": only if it divides a day. */
  private fun everyFewHours(text: String): Int? =
      EVERY_FEW_HOURS.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.toIntOrNull() }
          ?.takeIf { it in 1..HOURS_IN_A_DAY && HOURS_IN_A_DAY % it == 0 }
          ?.let { HOURS_IN_A_DAY / it }

  /**
   * "matin et soir", "morning, noon and evening", "abends": one dose at each time named. "Le soir
   * au coucher" names one time twice, so it counts once.
   */
  private fun namedTimes(text: String): Int? {
    val named = AT_BEDTIME_IN_THE_EVENING.replace(text, "soir")
    return TIMES_OF_DAY.count { it.containsMatchIn(named) }.takeIf { it > 0 }
  }

  /**
   * Once a day, said without a number of times ("daily", "1 par jour", "par jour" alone). Not after
   * a number other than 1 ("2 daily"): it can mean two tablets at once or twice a day.
   */
  private fun onceADay(text: String): Int? =
      1.takeIf {
        DAILY.any { it.containsMatchIn(text) } && !NUMBER_BEFORE_DAILY.containsMatchIn(text)
      }

  private val readers: List<(String) -> Int?> =
      listOf(::schemeCount, ::timesADay, ::wordCount, ::everyFewHours, ::namedTimes, ::onceADay)

  private const val HOURS_IN_A_DAY = 24

  private val IF_NEEDED =
      listOf(
          Regex("""\b(?:si besoin|au besoin|en reserve|if needed|as needed|when needed|prn)\b"""),
          Regex("""\b(?:bei bedarf)\b"""),
          Regex("""\bsi (?:nausees?|douleurs?|fievre|anxiete)\b"""),
      )

  private val LESS_THAN_DAILY =
      listOf(
          // Weekly or monthly: "1x/semaine", "once a week", "2 mal pro Woche", "hebdomadaire"
          Regex("""(?:\b(?:par|per|a|pro)\s+|/\s*)(?:semaine|sem|week|woche|mois|month|monat)\b"""),
          Regex("""\b(?:weekly|monthly|hebdomadaire|mensuel(?:le)?|wochentlich|monatlich)\b"""),
          // Every few days or weeks: "tous les 21 jours", "every 2 days", "alle 3 Wochen"
          Regex("""\b(?:tous les|toutes les|every|alle)\s+\d+\s*(?:jours?|days?|tage?)\b"""),
          Regex("""\b(?:toutes les|every|alle)\s+\d+\s*(?:semaines?|weeks?|wochen?)\b"""),
          Regex("""\b(?:every other day|un jour sur deux|1 jour sur 2|jeden zweiten tag)\b"""),
      )

  private val SCHEME = Regex("""(?<![\d-])(\d)\s*-\s*(\d)\s*-\s*(\d)(?:\s*-\s*(\d))?(?![\d-])""")

  private val TIMES_A_DAY =
      listOf(
          Regex(
              """\b(\d{1,2})\s*(?:x|fois|times|mal)\s*(?:/|par|per|a|pro)?\s*(?:jour|j|day|d|tag)\b"""
          ),
          // "x" or "mal" is needed: "2 daily" can mean two tablets once a day
          Regex("""\b(\d{1,2})\s*(?:x|mal)\s*(?:taglich|daily|quotidien(?:ne)?)\b"""),
          Regex("""(?:\bx\s*)?\b(\d{1,2})\s*/\s*(?:jour|j|day|d)\b"""),
      )

  private val WORD_COUNTS =
      linkedMapOf(
          Regex("""\b(?:once|one time)\s+(?:a|per)\s+day\b|\bonce daily\b""") to 1,
          Regex("""\btwice\s+(?:a|per)\s+day\b|\btwice daily\b""") to 2,
          Regex("""\bthree times\s+(?:a|per)\s+day\b|\bthree times daily\b""") to 3,
          Regex("""\bfour times\s+(?:a|per)\s+day\b|\bfour times daily\b""") to 4,
          Regex("""\bune fois par jour\b|\beinmal taglich\b|\b(?:qd|od)\b""") to 1,
          Regex("""\bdeux fois par jour\b|\bzweimal taglich\b|\bbid\b""") to 2,
          Regex("""\btrois fois par jour\b|\bdreimal taglich\b|\btid\b""") to 3,
          Regex("""\bquatre fois par jour\b|\bviermal taglich\b|\bqid\b""") to 4,
      )

  private val EVERY_FEW_HOURS =
      listOf(
          Regex("""\bq(\d{1,2})h\b"""),
          Regex(
              """\b(?:tous les|toutes les|every|alle)\s+(\d{1,2})\s*(?:h|heures?|hours?|stunden?)\b"""
          ),
      )

  /** The times of a day a dose can be named by, each counted once. */
  private val TIMES_OF_DAY =
      listOf(
          Regex("""\b(?:matin|morning|morgens)\b"""),
          Regex("""\b(?:midi|noon|lunch|mittags)\b"""),
          Regex("""\b(?:soir|evening|abends)\b"""),
          Regex("""\b(?:coucher|nuit|night|bedtime|zur nacht)\b"""),
      )

  private val NUMBER_BEFORE_DAILY =
      Regex("""\b(?:[02-9]|\d{2,})\s*(?:daily|taglich|quotidien(?:ne)?)\b""")

  private val AT_BEDTIME_IN_THE_EVENING = Regex("""\bsoir\s+(?:au|avant le)\s+coucher\b""")

  /**
   * Once a day without a number of times. "2 par jour" isn't read: it can mean two tablets at once.
   */
  private val DAILY =
      listOf(
          Regex("""\b(?:daily|every day|tous les jours|taglich|quotidien(?:ne)?)\b"""),
          Regex("""\b1\s*(?:par|per|a|pro)\s*(?:jour|day|tag)\b"""),
          Regex("""^(?:par jour|per day|a day|pro tag)$"""),
      )
}
