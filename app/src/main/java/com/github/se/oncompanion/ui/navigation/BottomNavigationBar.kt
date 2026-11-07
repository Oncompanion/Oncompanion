// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.full

/**
 * The top-level destinations reachable from the bottom navigation bar, in display order.
 *
 * As recommended by Material 3, [icon] is outlined and [selectedIcon] is its filled version, so the
 * selected tab also stands out by its icon.
 */
enum class Tab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
    val testTag: String,
) {
  OVERVIEW(
      Route.OVERVIEW,
      R.string.tab_overview,
      R.drawable.ic_tab_overview,
      R.drawable.ic_tab_overview_selected,
      C.Tag.bottom_navigation_tab_overview,
  ),
  PLANNING(
      Route.PLANNING,
      R.string.planning_title,
      R.drawable.ic_tab_planning,
      R.drawable.ic_tab_planning_selected,
      C.Tag.bottom_navigation_tab_planning,
  ),
  EVENTS(
      Route.EVENTS,
      R.string.events_title,
      R.drawable.ic_tab_events,
      R.drawable.ic_tab_events_selected,
      C.Tag.bottom_navigation_tab_events,
  ),
}

/**
 * The bottom navigation bar shown on the three top-level screens (Figma: "Navigation bar"): 80 dp
 * on Surface Container, three equal tabs, the selected one with a dawn indicator and an ink label.
 *
 * Each screen passes its own [selectedTab], so the highlighted tab always matches the screen.
 */
@Composable
fun BottomNavigationBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      color = MaterialTheme.colorScheme.surfaceContainer,
      modifier = modifier.testTag(C.Tag.bottom_navigation_bar),
  ) {
    Row(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(NavigationBarDefaults.windowInsets)
            .selectableGroup()
    ) {
      Tab.entries.forEach { tab ->
        TabItem(
            tab = tab,
            selected = tab == selectedTab,
            onClick = { onTabSelected(tab) },
            modifier = Modifier.weight(1f).testTag(tab.testTag),
        )
      }
    }
  }
}

/**
 * One tab of the bar: its icon in the 64 x 32 indicator, then its label. Material's own item has a
 * 56 dp indicator, so the Figma's is drawn here.
 */
@Composable
private fun TabItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
  val colors = MaterialTheme.colorScheme
  val interactions = remember { MutableInteractionSource() }
  Column(
      modifier
          .selectable(selected, interactions, indication = null, role = Role.Tab, onClick = onClick)
          .padding(top = Spacing.md, bottom = Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // The ripple stays inside the indicator, as on Material's bar
    Box(
        Modifier.size(width = 64.dp, height = 32.dp)
            .clip(MaterialTheme.shapes.full)
            .background(if (selected) colors.secondaryContainer else Color.Transparent)
            .indication(interactions, ripple()),
        contentAlignment = Alignment.Center,
    ) {
      // The label below already names the tab, so the icon is decorative
      Icon(
          painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
          contentDescription = null,
          tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
      )
    }
    Text(
        stringResource(tab.label),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
    )
  }
}
