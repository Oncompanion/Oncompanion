// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalTime

/**
 * The top app bar of a screen that isn't a main screen (DESIGN.md, Layout: "Other screens"): the
 * title and its back or close button on the 100 dp [PaperLakeBand], which also fills the status bar
 * above them, as in the Figma.
 *
 * @param navigationIcon the back arrow, or the close button of a form or a flow
 * @param time the time whose light the sky shows; now by default
 * @param windowInsets the insets the bar keeps clear, e.g. none when it scrolls with a short form
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LakeTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    time: LocalTime = remember { LocalTime.now() },
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
  val onHorizon = MaterialTheme.lakeColors.onHorizon
  PaperLakeBand(time, modifier) {
    // Transparent, so the sky shows behind the title
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = navigationIcon,
        actions = actions,
        windowInsets = windowInsets,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = onHorizon,
                navigationIconContentColor = onHorizon,
                actionIconContentColor = onHorizon,
            ),
    )
  }
}
