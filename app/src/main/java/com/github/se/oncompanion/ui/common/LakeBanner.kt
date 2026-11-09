// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.lakeColors

/** The tones of the Figma "Inline banner". */
enum class LakeBannerTone {
  /** On a neutral container: a notice, e.g. a refresh failed but the list stays on screen. */
  Neutral,
  /**
   * On the dawn container, for something that isn't final yet (e.g. not synced, a draft to check, a
   * list saved earlier). Never only because the phone is offline: reads use the cache.
   */
  Waiting,
}

/**
 * A lasting note at the top of content that stays on screen (Figma: "Inline banner" in Shared
 * patterns): the info icon, [message], then an optional [actionLabel] such as "Retry", below the
 * message when it doesn't fit beside it. The icon is a neutral notice on purpose, not a red warning
 * sign.
 *
 * @param actionTestTag the test tag of the action, shown when [actionLabel] isn't null
 */
@Composable
fun LakeBanner(
    message: String,
    modifier: Modifier = Modifier,
    tone: LakeBannerTone = LakeBannerTone.Neutral,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    actionTestTag: String = "",
) {
  val colors = MaterialTheme.colorScheme
  val (container, content) =
      when (tone) {
        LakeBannerTone.Neutral -> colors.surfaceContainerHigh to colors.onSurface
        LakeBannerTone.Waiting -> colors.secondaryContainer to colors.onSecondaryContainer
      }
  Surface(
      shape = MaterialTheme.shapes.medium,
      color = container,
      contentColor = content,
      modifier = modifier.fillMaxWidth(),
  ) {
    Row(
        Modifier.heightIn(min = MIN_HEIGHT).padding(start = Spacing.md, end = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      // The message says what it is about
      Icon(painterResource(R.drawable.ic_info), contentDescription = null, Modifier.size(ICON_SIZE))
      // The Figma's 4 dp above and below go around the message only, so the action keeps the
      // banner's whole 48 dp as its touch target
      MessageAndAction(
          message = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = Spacing.xs),
            )
          },
          action =
              actionLabel?.let {
                {
                  LinkAction(
                      it,
                      MaterialTheme.lakeColors.primaryText,
                      onAction,
                      Modifier.testTag(actionTestTag),
                  )
                }
              },
          gap = Spacing.md,
          modifier = Modifier.weight(1f),
      )
    }
  }
}

// The height of a one-line banner in the Figma
private val MIN_HEIGHT = 48.dp

// The size of the info icon in the Figma
private val ICON_SIZE = 24.dp
