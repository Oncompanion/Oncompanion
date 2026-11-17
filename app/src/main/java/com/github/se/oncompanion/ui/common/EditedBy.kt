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
 * "Edited by Sam" under an entry a caregiver edited for the patient (US-46), so the patient knows
 * who changed it. It sits under [AddedBy], which never changes, and looks the same. Shared by every
 * section a caregiver can edit.
 */
@Composable
fun EditedBy(name: String, modifier: Modifier = Modifier) {
  Text(
      text = stringResource(R.string.edited_by, name),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = modifier.testTag(C.Tag.edited_by),
  )
}
