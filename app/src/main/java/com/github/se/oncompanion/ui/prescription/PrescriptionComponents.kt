// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How the prescription screens write a date: "29 Sep 2026". One formatter, so the list, the detail,
 * the form and the notices always agree. The app's UI is in English.
 */
internal val prescriptionDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * A centered title and a hint under it, for the messages the prescription screens show instead of
 * their content (e.g. an empty list, a prescription that can't be loaded), so they all look alike.
 */
@Composable
internal fun MessageTexts(title: String, hint: String) {
  Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
  Text(
      text = hint,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
  )
}
