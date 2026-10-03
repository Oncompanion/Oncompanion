package com.github.se.oncompanion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen

/**
 * The app's navigation graph. Each feature lives in its own nested graph ([Route]).
 *
 * The sign-in and onboarding screens are placeholders until their PRs land.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startRoute: String = Route.AUTH,
) {
  val navigationActions = remember(navController) { NavigationActions(navController) }

  NavHost(navController = navController, startDestination = startRoute) {
    navigation(startDestination = Screen.SIGN_IN, route = Route.AUTH) {
      composable(Screen.SIGN_IN) {
        PlaceholderScreen(
            title = stringResource(R.string.sign_in_title),
            testTag = C.Tag.sign_in_screen,
        )
      }
    }

    navigation(startDestination = Screen.ONBOARDING_ROLE, route = Route.ONBOARDING) {
      composable(Screen.ONBOARDING_ROLE) {
        PlaceholderScreen(
            title = stringResource(R.string.onboarding_role_title),
            testTag = C.Tag.onboarding_role_screen,
        )
      }
      composable(Screen.ONBOARDING_INFORMATION) {
        PlaceholderScreen(
            title = stringResource(R.string.onboarding_information_title),
            testTag = C.Tag.onboarding_information_screen,
        )
      }
    }

    navigation(startDestination = Screen.OVERVIEW, route = Route.OVERVIEW) {
      composable(Screen.OVERVIEW) { OverviewScreen(navigationActions) }
    }
  }
}
