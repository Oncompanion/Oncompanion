// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication.scan

import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.scan.ClassifiedLine
import com.github.se.oncompanion.model.medication.scan.ClassifiedRow
import com.github.se.oncompanion.model.medication.scan.LineKind
import com.github.se.oncompanion.model.medication.scan.PrescriptionLexicon
import com.github.se.oncompanion.model.medication.scan.matchable

/** A medication read on a prescription, each detail with whether it can be trusted. */
internal data class ScannedMedication(
    val name: Reading<String>,
    val dosage: Reading<String>,
    val frequency: Reading<String>,
    val durationDays: Reading<Int?>,
)

/**
 * The medications of a prescription, in the order they are written. Each starts at a line with a
 * name and a strength; the dosing lines below it, or beside it in a table row, are its own until
 * the next medication or a line that is something else ("Contrôle sanguin dans 10 jours"). A line
 * beside a medication that is something else, like a stamp, is skipped.
 */
internal fun medicationsIn(rows: List<ClassifiedRow>): List<ScannedMedication> {
  val medications = mutableListOf<MutableList<ClassifiedLine>>()
  var current: MutableList<ClassifiedLine>? = null
  for (row in rows) {
    var medicationInRow = false
    for (line in row.lines) {
      when (line.kind) {
        LineKind.MEDICATION -> {
          current = mutableListOf(line).also { medications += it }
          medicationInRow = true
        }
        LineKind.DOSING -> current?.add(line)
        LineKind.NOISE,
        LineKind.LABEL,
        LineKind.TABLE_HEADING -> Unit
        LineKind.OTHER -> if (!medicationInRow) current = null
        else -> current = null
      }
    }
  }
  return medications.map(::medicationOf)
}

/** The medication written on [lines]: its name and strength first, then how to take it. */
private fun medicationOf(lines: List<ClassifiedLine>): ScannedMedication {
  val first = lines.first().text
  // A medication line always has a strength: the name ends with it
  val strength = checkNotNull(PrescriptionLexicon.strengthIn(matchable(first)))
  val name = first.substring(0, strength.last + 1).replace(NUMBERING, "").trim(*EDGES)
  // After the strength, words before the first dosing word describe the form ("comprimés")
  val after = first.substring(strength.last + 1)
  val dosingAfter = PrescriptionLexicon.dosingStartIn(matchable(after))?.let(after::substring)
  val dosing = (listOfNotNull(dosingAfter) + lines.drop(1).map { it.text }).joinToString(" ")

  val matched = matchable(dosing)
  val quantity = PrescriptionLexicon.doseQuantityIn(matched)
  val duration = PrescriptionLexicon.durationIn(matched)
  // What is left once the quantity and the duration are taken out says how often
  val frequency =
      listOfNotNull(quantity, duration?.range)
          .sortedByDescending { it.first }
          .fold(dosing) { text, range -> text.removeRange(range) }
          .replace(SPACES, " ")
          .trim(*EDGES)

  val linesSure = lines.all { it.line.confidence >= SURE }
  // A duration word left in the frequency ("twice a day S days") is a duration that couldn't be
  // read: neither the frequency nor "no duration" can be trusted
  val unreadDuration =
      UNREAD_DURATION.containsMatchIn(matchable(frequency).replace(EVERY_FEW_DAYS, ""))
  val days = duration?.days?.takeIf(Medication::isValidDuration)
  return ScannedMedication(
      name = limited(name, sure = lines.first().line.confidence >= SURE && !looksSplit(name)),
      dosage = limited(quantity?.let(dosing::substring).orEmpty(), sure = linesSure),
      frequency = limited(frequency, sure = linesSure && !unreadDuration),
      durationDays =
          Reading(
              days,
              sure =
                  linesSure &&
                      !unreadDuration &&
                      (duration == null || (duration.exact && days != null)),
          ),
  )
}

/** [text] cut to what a medication can store; not sure if empty or cut. */
private fun limited(text: String, sure: Boolean): Reading<String> {
  val kept = text.take(Medication.MAX_TEXT_LENGTH).trim()
  return Reading(kept, sure = sure && kept.isNotEmpty() && kept == text)
}

/**
 * Whether ML Kit seems to have split a word of [name]: a short lowercase piece after its first word
 * ("Métoclopram ide 10 mg"). It reads such a line as sure, so the name must be flagged here.
 */
private fun looksSplit(name: String): Boolean =
    name.split(" ").drop(1).takeWhile { it.none(Char::isDigit) }.any { FRAGMENT.matches(it) }

/** "1. " or "2) " before a medication in a numbered list, also read as "l. " or "I) ". */
private val NUMBERING = Regex("""^\s*[\dlIoO]{1,2}\s*[.)]\s+""")
private val FRAGMENT = Regex("""\p{Ll}{1,3}""")
private val SPACES = Regex("""\s+""")

/** A day, week or month that isn't part of a frequency ("par jour", "a day", "2x/jour"). */
private val UNREAD_DURATION =
    Regex(
        """(?<!\bpar )(?<!\ba )(?<!\bper )(?<!\bevery )(?<!\bchaque )(?<!\beach )(?<!/)""" +
            """\b(?:jours?|days?|semaines?|weeks?|mois|months?)\b"""
    )

/** A cycle, which says how often and not for how long: "tous les 21 jours", "every 2 days". */
private val EVERY_FEW_DAYS =
    Regex("""\b(?:tous les|toutes les|every)\s+\d+\s*(?:jours?|days?|semaines?|sem|weeks?)\b""")
private val EDGES = charArrayOf(' ', ',', ';', ':', '.', '-', '·')
