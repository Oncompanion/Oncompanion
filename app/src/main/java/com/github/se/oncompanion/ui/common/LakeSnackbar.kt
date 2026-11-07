// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.Spacing

/**
 * The Figma "Snackbar": flat (Material's snackbar always casts a shadow, the Figma's doesn't), on
 * Inverse Surface with Medium corners, the message then a close button when it asks for one. No
 * snackbar has an action (e.g. Retry) yet.
 */
@Composable
internal fun LakeSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  val visuals = data.visuals
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = colors.inverseSurface,
      contentColor = colors.inverseOnSurface,
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        Modifier.heightIn(min = 48.dp)
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          visuals.message,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(vertical = Spacing.sm),
      )
      if (visuals.withDismissAction) {
        IconButton(onClick = data::dismiss) {
          Icon(
              painterResource(R.drawable.ic_close),
              contentDescription = stringResource(R.string.snackbar_dismiss),
          )
        }
      }
    }
  }
}
