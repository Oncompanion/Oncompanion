// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

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
