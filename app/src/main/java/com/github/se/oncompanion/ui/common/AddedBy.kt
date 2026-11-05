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
 * doctor know who logged it. Shared by every section a caregiver can add to.
 */
@Composable
fun AddedBy(name: String, modifier: Modifier = Modifier) {
  Text(
      text = stringResource(R.string.symptom_added_by, name),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier.testTag(C.Tag.added_by),
  )
}
