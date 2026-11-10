// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Drafts the add symptom form from what the patient said ([transcript], in [locale]'s language:
 * French or English), at [now]. Only what was said plainly is filled in: the symptoms named and not
 * negated, an intensity said on a scale of 10, and when it was felt. Anything unclear or
 * contradictory stays empty, and nothing is concluded from what was said: the app organizes, it
 * doesn't interpret (see AGENTS.md). The whole transcript goes into the notes.
 */
fun parseSpokenSymptom(transcript: String, locale: Locale, now: ZonedDateTime): SymptomDraft {
  val words = spokenWords(transcript)
  val vocabulary = SpokenVocabulary.of(locale.language)
  val (date, time) = SpokenWhen(words, vocabulary, now).resolve()
  return SymptomDraft(
      types = symptomsSaid(words, vocabulary),
      intensity = intensitySaid(words, vocabulary),
      date = date,
      time = time,
      notes = transcript.trim().take(SymptomEntry.MAX_NOTES_LENGTH),
  )
}

/**
 * [text] as words to compare with a [SpokenVocabulary]: lowercase, without accents or apostrophes,
 * with "3:30" as "3h30", "p.m." as "pm", and punctuation that ends a clause kept as ",". The
 * recognizer adds punctuation on some phones and not on others, so it is never required.
 */
internal fun spokenWords(text: String): List<String> {
  val marked =
      text
          .lowercase(Locale.ROOT)
          .replace("œ", "oe")
          .replace(Regex("\\b([ap])\\.\\s?m\\b\\.?"), "$1m")
          .replace(Regex("(\\d)(am|pm)\\b"), "$1 $2")
          .replace(Regex("(\\d{1,2})[:.](\\d{2})"), "$1h$2")
  return Normalizer.normalize(marked, Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace(Regex("[.,;:!?]+"), " , ")
      .replace("/", " / ")
      .replace(Regex("[^a-z0-9/,*]+"), " ")
      .split(' ')
      .filter { it.isNotEmpty() }
}

/** How many words of [phrase] match [words] from [start], or 0 if it doesn't match there. */
private fun matchAt(words: List<String>, start: Int, phrase: Phrase): Int {
  if (start + phrase.size > words.size) return 0
  val matches =
      phrase.indices.all { k ->
        val word = phrase[k]
        val said = words[start + k]
        if (word.endsWith('*')) said.startsWith(word.dropLast(1)) else said == word
      }
  return if (matches) phrase.size else 0
}

/** The longest of [phrases] that matches [words] at [start], with its length. */
private fun <T> longestAt(words: List<String>, start: Int, phrases: List<Pair<Phrase, T>>) =
    phrases
        .map { (phrase, value) -> matchAt(words, start, phrase) to value }
        .filter { it.first > 0 }
        .maxByOrNull { it.first }

/** How many words of any of [phrases] match at [start], or 0. */
private fun anyAt(words: List<String>, start: Int, phrases: List<Phrase>): Int =
    phrases.maxOfOrNull { matchAt(words, start, it) } ?: 0

/** A negation reaches a symptom said at most this many words after it ("no nausea or headache"). */
private const val NEGATION_REACH = 3

/**
 * The symptoms in [words], in the order they were first said, without those negated. A symptom both
 * said and negated ("headache this morning, no headache now") is left out: not guessed.
 */
private fun symptomsSaid(words: List<String>, vocabulary: SpokenVocabulary): List<SymptomType> {
  val said = mutableListOf<SymptomType>()
  val negated = mutableSetOf<SymptomType>()
  // Where the last negation of the current clause was said
  var negation = Int.MIN_VALUE / 2
  var i = 0
  while (i < words.size) {
    val ignored = anyAt(words, i, vocabulary.ignored)
    val symptom = longestAt(words, i, vocabulary.symptoms)
    when {
      words[i] in vocabulary.clauseBreaks -> negation = Int.MIN_VALUE / 2
      ignored > 0 -> {
        i += ignored
        continue
      }
      symptom != null -> {
        val (length, type) = symptom
        val phrase = words.subList(i, i + length)
        // "pas faim" or "can't sleep" is the symptom itself, not its negation
        val negatesItself = phrase.any { isNegation(it, vocabulary) }
        if (!negatesItself && i - negation <= NEGATION_REACH) negated += type else said += type
        i += length
        continue
      }
      isNegation(words[i], vocabulary) &&
          (words[i] !in vocabulary.negationsAfterNe ||
              words.subList(maxOf(0, i - 2), i).any { it == "n" || it == "ne" }) -> negation = i
    }
    i++
  }
  return said.distinct().filter { it !in negated }
}

private fun isNegation(word: String, vocabulary: SpokenVocabulary) =
    word in vocabulary.negations || word in vocabulary.negationsAfterNe

/** A number said in digits or in words, or `null`. */
private fun numberAt(words: List<String>, i: Int, vocabulary: SpokenVocabulary): Int? =
    words.getOrNull(i)?.let { it.toIntOrNull() ?: vocabulary.numbers[it] }

/**
 * The intensity said as "N out of 10" (or "N/10", "N sur dix"), if it is the only one said and from
 * [SymptomEntry.MIN_INTENSITY] to [SymptomEntry.MAX_INTENSITY]. A number alone is never an
 * intensity: it could be a time or a temperature.
 */
private fun intensitySaid(words: List<String>, vocabulary: SpokenVocabulary): Int? {
  val said =
      words.indices.mapNotNull { i ->
        val value = numberAt(words, i, vocabulary) ?: return@mapNotNull null
        val outOf = anyAt(words, i + 1, vocabulary.outOf)
        value.takeIf { outOf > 0 && numberAt(words, i + 1 + outOf, vocabulary) == 10 }
      }
  return said.distinct().singleOrNull()?.takeIf {
    it in SymptomEntry.MIN_INTENSITY..SymptomEntry.MAX_INTENSITY
  }
}

/**
 * When the symptom was felt, from [words] said at [now]: a duration ago ("2 hours ago"), a day
 * ("yesterday"), a time of day ("at 9 am", "à 15h"), or a day and a time. Vague moments ("this
 * morning") are left empty, as are contradicting ones, times that haven't come yet and any other
 * day said ("tomorrow", "lundi"), which a time alone would otherwise place on today.
 */
private class SpokenWhen(
    private val words: List<String>,
    private val vocabulary: SpokenVocabulary,
    private val now: ZonedDateTime,
) {
  private val durationsAgo = mutableListOf<Long>()
  private val daysAgo = mutableListOf<Long>()
  /** The hours each time of day said could be, and its minutes. */
  private val clockTimes = mutableListOf<Pair<List<Int>, Int>>()
  /** Whether a day other than today or [SpokenVocabulary.daysAgo] was said. */
  private var otherDay = false

  /** The day and time said, each `null` if not said or unclear. */
  fun resolve(): Pair<LocalDate?, LocalTime?> {
    read()
    val minutesAgo = durationsAgo.distinct()
    val days = daysAgo.distinct()
    // A duration ago already says the day and time: anything else said contradicts it
    val contradicts = minutesAgo.isNotEmpty() && (days.isNotEmpty() || clockTimes.isNotEmpty())
    if (otherDay || minutesAgo.size > 1 || days.size > 1 || contradicts) return null to null
    minutesAgo.singleOrNull()?.let {
      val at = now.minusMinutes(it).truncatedTo(ChronoUnit.MINUTES)
      return at.toLocalDate() to at.toLocalTime()
    }
    val date = days.singleOrNull()?.let { now.toLocalDate().minusDays(it) }
    val day = date ?: now.toLocalDate()
    // Only the hours that have already come on that day are possible
    val times =
        clockTimes
            .map { (hours, minutes) ->
              hours
                  .map { LocalTime.of(it, minutes) }
                  .singleOrNull { !day.atTime(it).atZone(now.zone).isAfter(now) }
            }
            .distinct()
    return date to times.singleOrNull()
  }

  private fun read() {
    var i = 0
    while (i < words.size) {
      i +=
          readDurationAgo(i).takeIf { it > 0 }
              ?: readDay(i).takeIf { it > 0 }
              ?: readOtherDay(i).takeIf { it > 0 }
              ?: readClock(i).takeIf { it > 0 }
              ?: 1
    }
  }

  /** "2 hours ago", "il y a 2 heures": how many words it takes, or 0. */
  private fun readDurationAgo(i: Int): Int {
    val before = anyAt(words, i, vocabulary.agoBefore)
    if (before > 0) {
      val (minutes, length) = durationAt(i + before) ?: return 0
      durationsAgo += minutes
      return before + length
    }
    val (minutes, length) = durationAt(i) ?: return 0
    val after = anyAt(words, i + length, vocabulary.agoAfter)
    if (after == 0) return 0
    durationsAgo += minutes
    return length + after
  }

  /** A duration in minutes at [i] ("2 hours", "an hour", "3h", "half an hour"), and its length. */
  private fun durationAt(i: Int): Pair<Long, Int>? {
    val half = anyAt(words, i, vocabulary.halfHour)
    if (half > 0) return 30L to half
    Regex("(\\d+)h").matchEntire(words.getOrNull(i) ?: return null)?.let {
      return it.groupValues[1].toLong() * 60 to 1
    }
    val count =
        numberAt(words, i, vocabulary) ?: (if (words[i] in vocabulary.articles) 1 else return null)
    val unit = words.getOrNull(i + 1)
    val minutes =
        when (unit) {
          in vocabulary.minuteUnits -> count.toLong()
          in vocabulary.hourUnits -> count * 60L
          else -> return null
        }
    return minutes.takeIf { it > 0 }?.let { it to 2 }
  }

  /** "yesterday", "avant-hier": how many words it takes, or 0. */
  private fun readDay(i: Int): Int {
    val (length, days) = longestAt(words, i, vocabulary.daysAgo) ?: return 0
    daysAgo += days
    return length
  }

  /** "tomorrow", "lundi": a day that can't be placed. How many words it takes, or 0. */
  private fun readOtherDay(i: Int): Int =
      anyAt(words, i, vocabulary.otherDays).also { if (it > 0) otherDay = true }

  /**
   * A time of day: "15h30", "3:30 pm", "9 o'clock", "8 heures du soir". A number alone isn't one.
   * Without "am" or "pm", an hour up to 12 could be morning or evening. Returns how many words it
   * takes, or 0.
   */
  private fun readClock(i: Int): Int {
    val word = words[i]
    val clock = Regex("(\\d{1,2})h(\\d{2})?").matchEntire(word)
    var length: Int
    val hour: Int
    val minutes: Int
    if (clock != null) {
      hour = clock.groupValues[1].toInt()
      minutes = clock.groupValues[2].ifEmpty { "0" }.toInt()
      length = 1
    } else {
      hour = numberAt(words, i, vocabulary) ?: return 0
      minutes = 0
      length = 1 + anyAt(words, i + 1, vocabulary.clockWords)
    }
    val morning = anyAt(words, i + length, vocabulary.morning)
    val afternoon = anyAt(words, i + length, vocabulary.afternoon)
    // A number needs "o'clock", "heures", "am" or "pm" to be a time
    if (length == 1 && clock == null && morning + afternoon == 0) return 0
    if (hour > 23 || minutes > 59) return 0
    length += morning + afternoon
    val hours =
        when {
          hour > 12 || hour == 0 -> listOf(hour)
          morning > 0 -> listOf(hour % 12)
          afternoon > 0 -> listOf(hour % 12 + 12)
          else -> listOf(hour % 12, hour % 12 + 12)
        }
    clockTimes += hours to minutes
    return length
  }
}
