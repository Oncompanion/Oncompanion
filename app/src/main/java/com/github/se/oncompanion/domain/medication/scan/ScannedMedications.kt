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
  // A medication line has a strength: the name ends with it. Without one (a listed medication,
  // or should the classifier and the reader disagree), the name is all before how to take it, to
  // check, rather than stopping the scan
  val strength = PrescriptionLexicon.strengthIn(matchable(first))
  val nameEnd =
      strength?.let { closedBracketEnd(first, combinationEnd(first, it.last + 1)) }
          ?: PrescriptionLexicon.dosingStartIn(matchable(first))
          ?: first.length
  val name =
      first
          .substring(0, nameEnd)
          .replace(NUMBERING, "")
          .trim(*EDGES)
          // ML Kit reads the I starting a name as l ("lbuprofène"): no name starts with a small l
          .replace(MISREAD_I, "I")
  // After the strength, the box's size ("30 comprimés") and the words before the first dosing
  // word describe the medication, not how to take it ("comprimés"). A large quantity is the box's
  // size only when the dose follows it, on this line or the next ones; alone, it is the dose
  // ("10 gouttes"), to check
  val afterName = first.substring(nameEnd)
  val packSize = PrescriptionLexicon.packSizeIn(matchable(afterName))
  val boxSize = packSize?.takeIf {
    val rest = listOf(afterName.substring(it.last + 1)) + lines.drop(1).map { line -> line.text }
    PrescriptionLexicon.doseQuantityIn(matchable(rest.joinToString(" "))) != null
  }
  val after = afterName.substring(boxSize?.let { it.last + 1 } ?: 0)
  val dosingStart = PrescriptionLexicon.dosingStartIn(matchable(after))
  val described = after.substring(0, dosingStart ?: after.length)
  val dosingAfter = dosingStart?.let(after::substring)
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
          // "x 2/j" is "2/j": the "x" only says "times"
          .replace(TIMES_BEFORE, "")
          // How many times the prescription can be renewed isn't how often to take it
          .replace(RENEWAL, "")
          .trim(*EDGES)

  val linesSure = lines.all { it.line.confidence >= SURE }
  // A duration word left in the frequency ("twice a day S days") is a duration that couldn't be
  // read: neither the frequency nor "no duration" can be trusted
  val unreadDuration = hasUnreadDuration(matchable(frequency).replace(EVERY_FEW_DAYS, ""))
  val days = duration?.days?.takeIf(Medication::isValidDuration)
  return ScannedMedication(
      name =
          limited(
              name,
              sure = strength != null && lines.first().line.confidence >= SURE && !looksSplit(name),
          ),
      // Without a quantity, a Swiss scheme gives it with the form ("1-0-1" and "comprimés" is
      // "1 comprimé"): deduced, not read, so to check
      dosage =
          limited(
              quantity?.let(dosing::substring) ?: dosageFromScheme(frequency, described),
              sure = linesSure && quantity != null && (packSize == null || boxSize != null),
          ),
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

/**
 * [end], or after the ")" that follows it when the name before [end] opened a bracket: a strength
 * in brackets ("Paracétamol (500 mg)") keeps its bracket closed.
 */
private fun closedBracketEnd(line: String, end: Int): Int {
  val name = line.substring(0, end)
  if (name.count { it == '(' } <= name.count { it == ')' }) return end
  return CLOSING_BRACKET.matchAt(line, end)?.let { it.range.last + 1 } ?: end
}

/**
 * Where the name ends on [line] when its first strength ends at [strengthEnd]: after the last
 * strength of a combination ("Calcium 500 mg + vitamine D3 400 UI").
 */
private fun combinationEnd(line: String, strengthEnd: Int): Int {
  var end = strengthEnd
  while (true) {
    val rest = matchable(line.substring(end))
    val plus = COMBINED_WITH.find(rest) ?: return end
    val next = PrescriptionLexicon.strengthIn(rest) ?: return end
    val dosing = PrescriptionLexicon.dosingStartIn(rest)
    if (next.first < plus.range.last || (dosing != null && dosing < next.first)) return end
    end += next.last + 1
  }
}

/**
 * The dosage a Swiss scheme says, when every dose of [frequency] is the same number ("1-0-1",
 * "2-0-0") and [described] names the form ("comprimés"): "1 comprimé". Empty otherwise ("1-0-2", or
 * no form written): never guessed.
 */
private fun dosageFromScheme(frequency: String, described: String): String {
  val doses =
      SCHEME.find(frequency)?.groupValues?.drop(1)?.filter { it.isNotEmpty() && it != "0" }
          ?: return ""
  val count = doses.distinct().singleOrNull() ?: return ""
  val form =
      PrescriptionLexicon.formIn(matchable(described))?.let(described::substring) ?: return ""
  return "$count ${if (count == "1") form.removeSuffix("s") else form}"
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
private val SCHEME = Regex("""^(\d)\s*-\s*(\d)\s*-\s*(\d)(?:\s*-\s*(\d))?(?!\d)""")
private val TIMES_BEFORE = Regex("""^[xX]\s+(?=\d)""")
private val MISREAD_I = Regex("""^l(?=[bcdfghjkmnpqrstvwxz])""")
private val COMBINED_WITH = Regex("""^\s*\+\s*""")
private val CLOSING_BRACKET = Regex("""\s*\)""")
private val RENEWAL =
    Regex(
        """[,;]?\s*\b(?:renouvelable|renewable|erneuerbar)\b(?:\s*\d+\s*(?:fois|x|times|mal)\b)?""",
        RegexOption.IGNORE_CASE,
    )

/**
 * Whether [text] has a day, week or month that isn't part of a frequency ("par jour", "2x/jour").
 */
private fun hasUnreadDuration(text: String): Boolean =
    DURATION_WORD.findAll(text).any { !PER.containsMatchIn(text.substring(0, it.range.first)) }

private val DURATION_WORD =
    Regex("""\b(?:jour|day|semaine|week|mois|month|tag|tage|tagen|woche|wochen|monat|monate)s?\b""")
private val PER = Regex("""(?:\b(?:par|a|per|every|chaque|each|pro|je|alle) |/)$""")

/** A cycle, which says how often and not for how long: "tous les 21 jours", "every 2 days". */
private val EVERY_FEW_DAYS =
    Regex(
        """\b(?:tous les|toutes les|every|alle)\s+\d+\s*""" +
            """(?:jour|day|semaine|sem|week|tagen|tage|wochen|woche)s?\b"""
    )
private val EDGES = charArrayOf(' ', ',', ';', ':', '.', '-', '·', '•', '–')
