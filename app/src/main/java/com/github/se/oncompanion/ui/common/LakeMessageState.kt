// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full

/**
 * Replaces the content when there is nothing to show: a load failed, the item no longer exists, or
 * a section isn't shared (Figma: "Message state" in Shared patterns). A neutral info tile, [title],
 * an optional [message], then an optional filled [actionLabel] such as "Retry", all centered. For
 * "nothing yet", screens show the Empty lake instead.
 *
 * @param actionTestTag the test tag of the button, shown when [actionLabel] isn't null
 */
@Composable
fun LakeMessageState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionTestTag: String = "",
) {
  val colors = MaterialTheme.colorScheme
  Column(
      modifier.fillMaxWidth().padding(Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // The round tile, kept apart from the texts by an 8 dp spacer between the gaps (24 dp in all)
    Surface(
        shape = MaterialTheme.shapes.full,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = Modifier.size(TILE_SIZE).testTag(C.Tag.message_state_icon),
    ) {
      Box(contentAlignment = Alignment.Center) {
        // Decorative: the title says what happened
        Icon(painterResource(R.drawable.ic_info), contentDescription = null)
      }
    }
    Spacer(Modifier.height(Spacing.sm))
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
    )
    if (message != null) {
      Text(
          message,
          style = MaterialTheme.typography.bodyMedium,
          color = colors.onSurfaceVariant,
          textAlign = TextAlign.Center,
      )
    }
    if (actionLabel != null) {
      LakeButton(
          text = actionLabel,
          onClick = onAction,
          // 12 dp more than the gap above it, as in the Figma
          modifier = Modifier.padding(top = Spacing.md).testTag(actionTestTag),
      )
    }
  }
}

// The round info tile of the Figma "Message state"
private val TILE_SIZE = 64.dp
