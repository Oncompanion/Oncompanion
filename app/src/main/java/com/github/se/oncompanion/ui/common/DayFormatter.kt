// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter

/**
 * A formatter for a calendar day in the phone's language: [skeleton] lists the fields to show (e.g.
 * "dMMMyyyy"), and the language decides their order and punctuation ("12 Mar 1968" in British
 * English, "Mar 12, 1968" in American English).
 */
@Composable
fun rememberDayFormatter(skeleton: String): DateTimeFormatter {
  val locale = LocalConfiguration.current.locales[0]
  return remember(locale, skeleton) {
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
  }
}
