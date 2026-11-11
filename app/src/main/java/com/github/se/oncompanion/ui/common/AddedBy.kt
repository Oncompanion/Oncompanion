// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

/**
 * "Added by Sam" under an entry a caregiver added for the patient (US-22), so the patient and their
 * doctor know who logged it. Shared by every section a caregiver can add to. A prescription the
 * patient hasn't confirmed yet ([isConfirmed] false) reads "Added by Sam · Not confirmed" (US-43):
 * the words say it, not only the color (Dawn, for what the patient should check), so it never looks
 * like a confirmed one.
 */
@Composable
fun AddedBy(name: String, modifier: Modifier = Modifier, isConfirmed: Boolean = true) {
  Text(
      text =
          if (isConfirmed) stringResource(R.string.symptom_added_by, name)
          else stringResource(R.string.added_by_not_confirmed, name),
      style = MaterialTheme.typography.bodySmall,
      color =
          if (isConfirmed) MaterialTheme.colorScheme.onSurfaceVariant
          else MaterialTheme.colorScheme.secondary,
      modifier = modifier.testTag(C.Tag.added_by),
  )
}
