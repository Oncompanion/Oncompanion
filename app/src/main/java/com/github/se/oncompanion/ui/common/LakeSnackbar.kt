// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
 * The Figma "Snackbar", for a [SnackbarHost]: flat (Material's snackbar always casts a shadow, the
 * Figma's doesn't), on Inverse Surface with Medium corners: the message, its action when it has one
 * (e.g. Retry), then a close button when it asks for one. A snackbar is never the only place to act
 * on something important: it goes away on its own.
 */
@Composable
fun LakeSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  val visuals = data.visuals
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = colors.inverseSurface,
      contentColor = colors.inverseOnSurface,
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        Modifier.heightIn(min = MIN_HEIGHT).padding(start = Spacing.lg, end = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // The Figma's 4 dp above and below go around the message only, so the action keeps the
      // snackbar's whole 48 dp as its touch target
      Text(
          visuals.message,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.weight(1f).padding(vertical = Spacing.xs),
      )
      visuals.actionLabel?.let { LinkAction(it, colors.inversePrimary, data::performAction) }
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

/**
 * Material's [SnackbarHost] showing our [LakeSnackbar], 16 dp from the sides as in the Figma. Every
 * screen uses it, so their messages look the same. As Material's snackbar, it keeps 12 dp from the
 * button or bar below it, so it never touches an action, and stops at 600 dp wide in landscape.
 */
@Composable
fun LakeSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
  SnackbarHost(hostState, modifier) {
    LakeSnackbar(
        it,
        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md).widthIn(max = MAX_WIDTH),
    )
  }
}

// The height of a one-line snackbar in the Figma
private val MIN_HEIGHT = 48.dp

// Material's widest snackbar
private val MAX_WIDTH = 600.dp
