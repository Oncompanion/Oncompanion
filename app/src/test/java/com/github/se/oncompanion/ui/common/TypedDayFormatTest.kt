// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TypedDayFormatTest {

  private val british = TypedDayFormat("dd/MM/yyyy")

  /** Each language types the day in its own order, with its own separator. */
  @Test
  fun followsThePhonesLanguage() {
    val day = LocalDate.of(1980, 12, 31)
    val cases =
        listOf(
            Locale.UK to Triple("DD/MM/YYYY", "31121980", "31/12/1980"),
            Locale.US to Triple("MM/DD/YYYY", "12311980", "12/31/1980"),
            Locale.GERMANY to Triple("DD.MM.YYYY", "31121980", "31.12.1980"),
            Locale.JAPAN to Triple("YYYY/MM/DD", "19801231", "1980/12/31"),
            // Its pattern has an invisible right-to-left mark next to each slash
            Locale("ar") to Triple("DD/MM/YYYY", "31121980", "31/12/1980"),
            // The Japanese calendar's pattern has an era: the Gregorian one is used instead
            Locale.forLanguageTag("ja-JP-u-ca-japanese") to
                Triple("YYYY/MM/DD", "19801231", "1980/12/31"),
        )
    for ((locale, expected) in cases) {
      val (placeholder, digits, shown) = expected
      val format = TypedDayFormat.of(locale)
      assertEquals("$locale", placeholder, format.placeholder)
      assertEquals("$locale", digits, format.digits(day))
      assertEquals("$locale", day, format.parse(digits))
      assertEquals("$locale", shown, format.shown(digits))
    }
  }

  /** Only a whole, real day is read: not an incomplete one, nor 31 February. */
  @Test
  fun readsOnlyWholeRealDays() {
    val cases =
        listOf(
            "31121980" to LocalDate.of(1980, 12, 31),
            "29022024" to LocalDate.of(2024, 2, 29),
            "29022023" to null,
            "31041980" to null,
            "00011980" to null,
            // Before the picker's first year: a typo, not a date of birth
            "01010000" to null,
            "12030968" to null,
            "31121899" to null,
            "01011900" to LocalDate.of(1900, 1, 1),
            "0112198" to null,
            "" to null,
        )
    for ((digits, expected) in cases) assertEquals(digits, expected, british.parse(digits))
  }

  @Test
  fun keepsOnlyDigits_atMostEight() {
    assertEquals("31121980", british.digitsOf("31/12/1980"))
    assertEquals("31121980", british.digitsOf("3112198099"))
    assertEquals("", british.digitsOf("ab"))
    // Digits typed on an Arabic or Persian keyboard are read as 0 to 9
    assertEquals("31121980", british.digitsOf("٣١١٢١٩٨٠"))
    assertEquals("31121980", british.digitsOf("۳۱۱۲۱۹۸۰"))
    assertNull(british.parse("31/12/80"))
  }

  /** The separators appear as the digits are typed, and the cursor skips them. */
  @Test
  fun separatorsAppearAsTheDigitsAreTyped() {
    val shownWhileTyping = listOf("3", "31", "31/1", "31/12", "31/12/1", "31/12/1980")
    for (shown in shownWhileTyping) assertEquals(shown, british.shown(british.digitsOf(shown)))

    val mapping = british.visualTransformation.filter(AnnotatedString("31121980")).offsetMapping
    val cursor = listOf(0 to 0, 2 to 2, 3 to 4, 4 to 5, 5 to 7, 8 to 10)
    for ((typed, shown) in cursor) {
      assertEquals("typed $typed", shown, mapping.originalToTransformed(typed))
      assertEquals("shown $shown", typed, mapping.transformedToOriginal(shown))
    }
    // On a separator, the cursor goes back to the digit before it
    assertEquals(2, mapping.transformedToOriginal(3))
    // While incomplete, no position goes past what was typed
    val partial = british.visualTransformation.filter(AnnotatedString("311")).offsetMapping
    assertEquals(4, partial.originalToTransformed(3))
    assertEquals(3, partial.transformedToOriginal(4))
  }

  private fun TypedDayFormat.shown(digits: String) =
      visualTransformation.filter(AnnotatedString(digits)).text.text

  /** A Persian phone types Gregorian years, like the picker, not Solar Hijri ones. */
  @Test
  fun persianPhone_typesGregorianYears() {
    val persian = TypedDayFormat.of(Locale("fa", "IR"))
    val day = LocalDate.of(1980, 12, 31)
    assertEquals(day, persian.parse(persian.digits(day)))
    assertEquals(true, persian.placeholder.contains("YYYY"))
    assertEquals(day, persian.parse(persian.digitsOf(persian.digits(day))))
  }

  /** A pattern that isn't a day doesn't crash the screen: it falls back to DD/MM/YYYY. */
  @Test
  fun aPatternWithoutADay_isRefused() {
    assertEquals(false, runCatching { TypedDayFormat("MM/yyyy") }.isSuccess)
    assertEquals("DD/MM/YYYY", TypedDayFormat("dd/MM/yyyy").placeholder)
  }
}
