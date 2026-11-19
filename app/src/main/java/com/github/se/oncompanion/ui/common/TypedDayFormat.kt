// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.text.format.DateFormat
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * How a calendar day is typed in a text field, in the order of the phone's language: the user types
 * the 8 digits and the separators appear on their own, e.g. "31/12/1980" on a British phone,
 * "12/31/1980" on an American one, "31.12.1980" on a German one.
 *
 * Digits of any script are read (e.g. the Arabic-Indic ones an Arabic keyboard types), and only
 * years from [MIN_YEAR], the picker's first, are days.
 *
 * @param pattern the phone's short date pattern, e.g. "dd/MM/yyyy"; its day, month and year give
 *   the order, and its first visible punctuation the separator
 */
class TypedDayFormat(pattern: String) {
  // The pattern without its quoted text, e.g. 'de' in a Spanish long date
  private val unquoted = pattern.replace(Regex("'[^']*'"), "")

  // The day, the month and the year, in the language's order
  private val fields: List<Char> = unquoted.filter { it in "dMy" }.toList().distinct()

  // Visible punctuation only: not a letter (e.g. an era), a space or an invisible right-to-left
  // mark
  private val separator: Char =
      unquoted.firstOrNull {
        !it.isLetterOrDigit() &&
            !it.isWhitespace() &&
            Character.getType(it) != Character.FORMAT.toInt()
      } ?: '/'
  private val digitsPattern = fields.joinToString("") { field(it) }

  init {
    require(fields.sorted() == listOf('M', 'd', 'y')) { "Not a day pattern: $pattern" }
  }

  // Strict: 31/02 is no day, rather than the last day of February
  private val formatter =
      DateTimeFormatter.ofPattern(digitsPattern.replace("yyyy", "uuuu"))
          .withResolverStyle(ResolverStyle.STRICT)

  /** What the field shows while empty, e.g. "DD/MM/YYYY". */
  val placeholder: String =
      fields.joinToString(separator.toString()) { field(it).uppercase(Locale.ROOT) }

  /** Adds the separators to the typed digits as they are shown, without changing the value. */
  val visualTransformation = VisualTransformation { text ->
    TransformedText(AnnotatedString(withSeparators(text.text)), offsets(text.text.length))
  }

  /** Keeps what can be typed: digits of any script, as 0 to 9, at most 8. */
  fun digitsOf(typed: String): String =
      typed
          .mapNotNull { Character.digit(it, 10).takeIf { d -> d >= 0 }?.digitToChar() }
          .joinToString("")
          .take(DIGITS)

  /** The 8 digits of [date], as they are typed. */
  fun digits(date: LocalDate): String = date.format(formatter)

  /**
   * The day [digits] stand for, or `null` while they are incomplete or no day: 31/02, or a year
   * before [MIN_YEAR] (e.g. 0968 for 1968).
   */
  fun parse(digits: String): LocalDate? =
      if (digits.length != DIGITS || !digits.all { it in '0'..'9' }) null
      else
          runCatching { LocalDate.parse(digits, formatter) }
              .getOrNull()
              ?.takeIf { it.year >= MIN_YEAR }

  // Where the separators go: after each field but the last
  private val breaks: List<Int> =
      fields.dropLast(1).runningFold(0) { at, f -> at + field(f).length }.drop(1)

  private fun withSeparators(digits: String): String = buildString {
    digits.forEachIndexed { i, digit ->
      if (i in breaks) append(separator)
      append(digit)
    }
  }

  // A position in the typed digits and the same position in what is shown, separators included:
  // the separator after field i is shown at breaks[i] + i
  private fun offsets(typed: Int) =
      object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int =
            offset + breaks.count { it < offset && it < typed }

        override fun transformedToOriginal(offset: Int): Int =
            (offset - breaks.withIndex().count { (i, at) -> at < typed && at + i < offset })
                .coerceIn(0, typed)
      }

  companion object {
    /** The number of digits of a typed day. */
    const val DIGITS = 8

    private fun field(letter: Char) = if (letter == 'y') "yyyy" else "$letter$letter"

    /** The first year that can be typed: the date picker's first. */
    @OptIn(ExperimentalMaterial3Api::class) val MIN_YEAR: Int = DatePickerDefaults.YearRange.first

    /**
     * The format of [locale]'s short dates, e.g. "dd/MM/yyyy" in British English, always in the
     * Gregorian calendar (a Thai or Persian phone would otherwise give its own calendar's years). A
     * pattern without a day, a month and a year falls back to "dd/MM/yyyy".
     */
    fun of(locale: Locale): TypedDayFormat {
      val gregorian =
          Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("ca", "gregory").build()
      return runCatching {
        TypedDayFormat(DateFormat.getBestDateTimePattern(gregorian, "ddMMyyyy"))
      }
          .getOrElse { TypedDayFormat("dd/MM/yyyy") }
    }
  }
}
