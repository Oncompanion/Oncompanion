// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication.scan

import java.text.Normalizer
import java.util.Locale

/**
 * [text] with the mistakes ML Kit predictably makes on a prescription undone, so dates, doses and
 * strengths can be recognized. Only what can't be meant otherwise is changed:
 * - in a token made of digits, a letter that looks like one is that digit ("o2.07.1965" →
 *   "02.07.1965", "170o" → "1700"); words like "Oncology" are never touched;
 * - a bar or l before a dose form is a 1 ("| tablet" → "1 tablet");
 * - spaces inside a date are removed ("11. 11.2026" → "11.11.2026").
 */
internal fun cleanedOcr(text: String): String {
  val digitsFixed = text.split(" ").joinToString(" ") { if (looksNumeric(it)) asDigits(it) else it }
  return digitsFixed
      .replace(MISREAD_ONE) { "1" }
      .replace(SPACED_DATE) { m ->
        val (day, separator, month, year) = m.destructured
        "$day$separator$month$separator$year"
      }
}

/**
 * [text] in the form used to compare it with the words of a prescription: lowercase, without
 * accents, with single spaces, so "Ondansétron" matches "ondansetron" and "Médecin" "medecin".
 */
internal fun comparable(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(ACCENTS, "")
        .lowercase(Locale.ROOT)
        .replace(SPACES, " ")
        .trim()

/** A token with at least one digit whose only letters are ones ML Kit mistakes for digits. */
private fun looksNumeric(token: String): Boolean =
    token.any { it.isDigit() } &&
        token.all { it.isDigit() || it in DIGIT_LOOKALIKES || !it.isLetter() }

private fun asDigits(token: String): String =
    token.map { DIGIT_LOOKALIKES[it] ?: it }.joinToString("")

/** Letters ML Kit reads in place of a digit, and that digit. */
private val DIGIT_LOOKALIKES = mapOf('o' to '0', 'O' to '0', 'l' to '1', 'I' to '1')

/** A bar, l or I standing alone before a dose form: a misread 1. */
private val MISREAD_ONE =
    Regex(
        """(?<!\S)[|lI](?=\s+(?:tablet|comprim|cp\b|g[ée]lule|capsule|injection|goutte|sachet|drop))""",
        RegexOption.IGNORE_CASE,
    )

/** A date with spaces around its separators, e.g. "11. 11.2026" or "29 / 09 / 2026". */
private val SPACED_DATE = Regex("""\b(\d{1,2})\s*([./])\s*(\d{1,2})\s*\2\s*(\d{4}|\d{2})\b""")

private val ACCENTS = Regex("""\p{Mn}+""")
private val SPACES = Regex("""\s+""")
