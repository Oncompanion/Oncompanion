// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.symptom

import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.symptom.SymptomType.APPETITE_LOSS
import com.github.se.oncompanion.model.symptom.SymptomType.BREATHLESSNESS
import com.github.se.oncompanion.model.symptom.SymptomType.FATIGUE
import com.github.se.oncompanion.model.symptom.SymptomType.FEVER
import com.github.se.oncompanion.model.symptom.SymptomType.HEADACHE
import com.github.se.oncompanion.model.symptom.SymptomType.NAUSEA
import com.github.se.oncompanion.model.symptom.SymptomType.PAIN
import com.github.se.oncompanion.model.symptom.SymptomType.SLEEP_PROBLEMS

/** A phrase as [spokenWords]; a word ending in `*` matches any word starting with the rest. */
internal typealias Phrase = List<String>

/**
 * The words [parseSpokenSymptom] understands in one language. Phrases are written as spoken and
 * compared after [spokenWords], so accents, case and apostrophes don't matter.
 *
 * @property symptoms phrases for each symptom; [SymptomType.OTHER] is never drafted
 * @property negations words that negate the symptom said soon after them, in the same clause
 * @property negationsAfterNe words that only negate after "ne" ("je n'ai plus de fièvre"), as
 *   "plus" alone also means "more"
 * @property clauseBreaks words that end what a negation applies to ("but", "mais")
 * @property ignored phrases that look like a negation or a symptom but are neither
 * @property numbers numbers said as words; digits are always understood
 * @property outOf what joins an intensity and its scale ("7 out of 10")
 * @property articles words that mean "one" before a duration ("an hour ago")
 * @property agoBefore what comes before a duration in the past ("il y a 2 heures")
 * @property agoAfter what comes after a duration in the past ("2 hours ago")
 * @property halfHour half an hour, as one phrase
 * @property minuteUnits, hourUnits the units of a duration
 * @property daysAgo days said by name, with how many days ago they are
 * @property clockWords what makes a number a time of day ("9 o'clock", "9 heures")
 * @property morning, afternoon what says whether a time is before or after noon
 */
internal class SpokenVocabulary(
    symptoms: Map<SymptomType, List<String>>,
    val negations: Set<String>,
    val negationsAfterNe: Set<String> = emptySet(),
    val clauseBreaks: Set<String>,
    ignored: List<String>,
    val numbers: Map<String, Int>,
    outOf: List<String>,
    val articles: Set<String>,
    agoBefore: List<String>,
    agoAfter: List<String>,
    halfHour: List<String>,
    val minuteUnits: Set<String>,
    val hourUnits: Set<String>,
    daysAgo: Map<String, Long>,
    clockWords: List<String>,
    morning: List<String>,
    afternoon: List<String>,
) {
  val symptoms: List<Pair<Phrase, SymptomType>> = symptoms.flatMap { (type, phrases) ->
    phrases.map { spokenWords(it) to type }
  }
  val ignored: List<Phrase> = phrases(ignored)
  val outOf: List<Phrase> = phrases(outOf)
  val agoBefore: List<Phrase> = phrases(agoBefore)
  val agoAfter: List<Phrase> = phrases(agoAfter)
  val halfHour: List<Phrase> = phrases(halfHour)
  /** Longest first, so "avant-hier" isn't read as "hier". */
  val daysAgo: List<Pair<Phrase, Long>> =
      daysAgo
          .map { (phrase, days) -> spokenWords(phrase) to days }
          .sortedByDescending { it.first.size }
  val clockWords: List<Phrase> = phrases(clockWords)
  val morning: List<Phrase> = phrases(morning)
  val afternoon: List<Phrase> = phrases(afternoon)

  private fun phrases(list: List<String>): List<Phrase> = list.map(::spokenWords)

  companion object {
    /** The vocabulary for [language] (an ISO 639 code): French for "fr", English otherwise. */
    fun of(language: String): SpokenVocabulary = if (language == "fr") FRENCH else ENGLISH

    private val ENGLISH =
        SpokenVocabulary(
            symptoms =
                mapOf(
                    FATIGUE to
                        listOf(
                            "tired",
                            "exhausted",
                            "fatigue*",
                            "worn out",
                            "wiped out",
                            "exhaustion",
                            "feel drained",
                            "feeling drained",
                            "felt drained",
                            "no energy",
                            "low energy",
                        ),
                    NAUSEA to
                        listOf(
                            "nause*",
                            "queasy",
                            "feel sick",
                            "feeling sick",
                            "felt sick",
                            "sick to my stomach",
                            "throw up",
                            "threw up",
                            "throwing up",
                            "vomit*",
                            "retch*",
                            "dry heav*",
                        ),
                    PAIN to
                        listOf(
                            "pain",
                            "pains",
                            "painful",
                            "hurt*",
                            "ache*",
                            "aching",
                            "sore*",
                            "backache*",
                            "stomachache*",
                        ),
                    FEVER to listOf("fever*", "a temperature", "high temperature"),
                    // The recognizer sometimes writes "head ache" in two words
                    HEADACHE to
                        listOf(
                            "headache*",
                            "head ache*",
                            "migraine*",
                            "head hurts",
                            "my head hurts",
                        ),
                    APPETITE_LOSS to
                        listOf(
                            "no appetite",
                            "lost my appetite",
                            "loss of appetite",
                            "not hungry",
                            "poor appetite",
                            "don't want to eat",
                            "don't feel like eating",
                            "didn't feel like eating",
                        ),
                    SLEEP_PROBLEMS to
                        listOf(
                            "can't sleep",
                            "couldn't sleep",
                            "didn't sleep",
                            "haven't slept",
                            "hardly slept",
                            "barely slept",
                            "slept badly",
                            "slept poorly",
                            "trouble sleeping",
                            "awake all night",
                            "keep waking up",
                            "kept waking up",
                            "tossing and turning",
                            "insomnia",
                        ),
                    BREATHLESSNESS to
                        listOf(
                            "short of breath",
                            "shortness of breath",
                            "out of breath",
                            "breathless*",
                            "can't breathe",
                            "hard to breathe",
                            "trouble breathing",
                            "difficulty breathing",
                            "can't catch my breath",
                        ),
                ),
            // "t" is what is left of "n't" ("don't" → "don t")
            negations =
                setOf("no", "not", "t", "without", "never", "nor", "neither", "none", "nothing"),
            clauseBreaks =
                setOf(
                    ",",
                    "but",
                    "and",
                    "though",
                    "although",
                    "however",
                    "except",
                    "then",
                    "otherwise",
                    "just",
                ),
            ignored =
                listOf(
                    "don't know",
                    "not sure",
                    // Not a symptom: "tired of waiting", "pain-free", "a drain was drained"...
                    "tired of",
                    "sick and tired",
                    "feel sick and tired",
                    "feeling sick and tired",
                    "pain free",
                    "fever free",
                    "hurt my feelings",
                    "no appetite problem*",
                    "no appetite issue*",
                    "no appetite loss",
                    "no energy problem*",
                    // "can't stop throwing up" negates nothing
                    "can't stop",
                    "couldn't stop",
                ),
            numbers =
                mapOf(
                    "zero" to 0,
                    "one" to 1,
                    "two" to 2,
                    "three" to 3,
                    "four" to 4,
                    "five" to 5,
                    "six" to 6,
                    "seven" to 7,
                    "eight" to 8,
                    "nine" to 9,
                    "ten" to 10,
                ),
            outOf = listOf("out of", "on", "over", "/"),
            articles = setOf("a", "an"),
            agoBefore = emptyList(),
            agoAfter = listOf("ago"),
            halfHour = listOf("half an hour"),
            minuteUnits = setOf("minute", "minutes", "min", "mins"),
            hourUnits = setOf("hour", "hours", "hr", "hrs"),
            daysAgo = mapOf("yesterday" to 1, "day before yesterday" to 2),
            clockWords = listOf("o'clock"),
            morning = listOf("am", "in the morning"),
            afternoon = listOf("pm", "in the afternoon", "in the evening", "at night"),
        )

    private val FRENCH =
        SpokenVocabulary(
            symptoms =
                mapOf(
                    FATIGUE to
                        listOf(
                            "fatigu*",
                            "épuis*",
                            "suis crevé",
                            "suis crevée",
                            "sens crevé",
                            "sens crevée",
                            "suis à plat",
                            "sens à plat",
                            "pas d'énergie",
                            "n'ai plus d'énergie",
                        ),
                    NAUSEA to
                        listOf("nausé*", "envie de vomir", "vomi*", "mal au cœur", "barbouill*"),
                    // "mal" alone isn't pain: "je me sens mal", "pas mal"
                    PAIN to
                        listOf(
                            "douleur*",
                            "mal au",
                            "mal aux",
                            "mal à la",
                            "mal à l'",
                            "mal partout",
                            "fait mal",
                            "mal de dos",
                            "mal de ventre",
                            "mal de gorge",
                            "maux de ventre",
                            "douloureu*",
                        ),
                    FEVER to listOf("fièvre*", "de la température"),
                    HEADACHE to
                        listOf(
                            "mal à la tête",
                            "mal de tête",
                            "maux de tête",
                            "mal au crâne",
                            "mal de crâne",
                            "fait mal à la tête",
                            "fait mal au crâne",
                            "migraine*",
                            "céphalée*",
                        ),
                    APPETITE_LOSS to
                        listOf(
                            "pas faim",
                            "plus faim",
                            "pas d'appétit",
                            "n'ai plus d'appétit",
                            "aucun appétit",
                            "perte d'appétit",
                            "manque d'appétit",
                            "pas envie de manger",
                        ),
                    SLEEP_PROBLEMS to
                        listOf(
                            "mal dormi",
                            "pas dormi",
                            "peu dormi",
                            "pas pu dormir",
                            "mal à dormir",
                            "peux pas dormir",
                            "arrive pas à dormir",
                            "insomnie*",
                            "mauvaise nuit",
                            "nuit blanche",
                            "dors mal",
                            "pas fermé l'œil",
                        ),
                    BREATHLESSNESS to
                        listOf(
                            "essouffl*",
                            "souffle court",
                            "manque de souffle",
                            "mal à respirer",
                            "respire mal",
                            "manque d'air",
                            "peine à respirer",
                        ),
                ),
            negations = setOf("pas", "sans", "aucun", "aucune", "jamais", "ni", "rien"),
            negationsAfterNe = setOf("plus"),
            clauseBreaks =
                setOf(",", "mais", "et", "cependant", "pourtant", "sauf", "puis", "sinon", "juste"),
            ignored =
                listOf(
                    // "pas mal de" is "quite a lot of"; "je ne sais pas" negates nothing
                    "pas mal de",
                    "sais pas",
                    // Not a symptom: out of stock, uneasy, "I struggle to", a nap, it saddens me
                    "est épuisé*",
                    "sont épuisé*",
                    "mal à l'aise",
                    "du mal à la",
                    "du mal à l'",
                    "sen* mal au",
                    "sen* mal aux",
                    "sen* mal à la",
                    "sen* mal à l'",
                    "un peu dormi",
                    "fait mal au cœur",
                ),
            numbers =
                mapOf(
                    "zéro" to 0,
                    "un" to 1,
                    "une" to 1,
                    "deux" to 2,
                    "trois" to 3,
                    "quatre" to 4,
                    "cinq" to 5,
                    "six" to 6,
                    "sept" to 7,
                    "huit" to 8,
                    "neuf" to 9,
                    "dix" to 10,
                ),
            outOf = listOf("sur", "/"),
            articles = emptySet(),
            agoBefore = listOf("il y a"),
            agoAfter = emptyList(),
            halfHour = listOf("une demi-heure", "une demie heure"),
            minuteUnits = setOf("minute", "minutes", "min"),
            hourUnits = setOf("heure", "heures", "h"),
            daysAgo = mapOf("hier" to 1, "avant-hier" to 2),
            clockWords = listOf("h", "heure", "heures"),
            morning = listOf("du matin"),
            afternoon = listOf("de l'après-midi", "du soir"),
        )
  }
}
