package com.github.se.oncompanion.ui.overview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.navigation.NavigationActions

/**
 * Placeholder for the Overview (home) screen, the destination after signing in.
 *
 * The owner of US-23 ("See my day at a glance") replaces this with the real screen.
 */
@Suppress("UNUSED_PARAMETER") // navigationActions will be used by the real Overview screen
@Composable
fun OverviewScreen(navigationActions: NavigationActions, modifier: Modifier = Modifier) {
  PlaceholderScreen(
      title = stringResource(R.string.overview_title),
      testTag = C.Tag.overview_screen,
      modifier = modifier,
  )
}
