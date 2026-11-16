// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.PaperLakeBand
import com.github.se.oncompanion.ui.common.PaperLakeStatusBar
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.LocalTime

/**
 * The frame of an onboarding step (Figma "US-1 / Onboarding – Role" and "– Information"): the Paper
 * lake band, the step's question and why it's asked, its [content], then its [actions] at the
 * bottom of the screen when there's room.
 *
 * Everything scrolls together, the band and the margins too: on a small phone held sideways, fixed
 * ones would take most of the height left above the keyboard. A strip in the sky's colour stays
 * behind the status bar, so the scrolled step never passes under the clock and icons.
 *
 * @param innerPadding the Scaffold's: kept clear of the system bars, except the band, which goes
 *   under the status bar as in the Figma
 */
@Composable
internal fun OnboardingStep(
    title: String,
    subtitle: String,
    innerPadding: PaddingValues,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
  // The light of when the step opened: the sky doesn't change while the user answers
  val openedAt = remember { LocalTime.now() }
  val layoutDirection = LocalLayoutDirection.current
  Box(Modifier.fillMaxSize()) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                .verticalScroll(rememberScrollState())
    ) {
      PaperLakeBand(openedAt, Modifier.testTag(C.Tag.onboarding_band))
      Column(Modifier.padding(horizontal = Spacing.xl).padding(top = Spacing.xl)) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xxl))
        content()
      }
      // Pushes the actions to the bottom of the screen when there's room
      Spacer(Modifier.weight(1f).heightIn(min = Spacing.xxl))
      Column(
          Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
          content = actions,
      )
    }
    // Drawn last, so the scrolled step passes under it rather than under the clock and icons
    PaperLakeStatusBar(openedAt, Modifier.testTag(C.Tag.onboarding_status_bar))
  }
}
