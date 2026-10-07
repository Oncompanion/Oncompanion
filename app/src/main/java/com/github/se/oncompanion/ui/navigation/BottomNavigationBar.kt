package com.github.se.oncompanion.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C

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
 * The bottom navigation bar shown on the three top-level screens (Figma: "Navigation bar").
 *
 * Each screen passes its own [selectedTab], so the highlighted tab always matches the screen.
 */
@Composable
fun BottomNavigationBar(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
  ShortNavigationBar(modifier = modifier.testTag(C.Tag.bottom_navigation_bar)) {
    Tab.entries.forEach { tab ->
      val selected = tab == selectedTab
      ShortNavigationBarItem(
          selected = selected,
          onClick = { onTabSelected(tab) },
          // The label below already names the tab, so the icon is decorative
          icon = {
            Icon(
                painter = painterResource(if (selected) tab.selectedIcon else tab.icon),
                contentDescription = null,
            )
          },
          label = { Text(stringResource(tab.label)) },
          modifier = Modifier.testTag(tab.testTag),
      )
    }
  }
}
