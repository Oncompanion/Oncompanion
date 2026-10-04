package com.github.se.oncompanion.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.AfterSignIn
import com.github.se.oncompanion.ui.auth.SignInScreen
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen

/**
 * The app's navigation graph. Each feature lives in its own nested graph ([Route]).
 *
 * The onboarding screens, and every feature added with [placeholderGraph], are placeholders until
 * their PRs land.
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
        SignInScreen(
            onSignedIn = { next ->
              navigationActions.navigateAndClearBackStack(
                  when (next) {
                    AfterSignIn.ONBOARDING -> Route.ONBOARDING
                    AfterSignIn.OVERVIEW -> Route.OVERVIEW
                  }
              )
            }
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

    // Bottom bar tabs, next to Overview
    placeholderGraph(
        Route.PLANNING,
        Screen.PLANNING,
        R.string.planning_title,
        C.Tag.planning_screen,
    )
    placeholderGraph(Route.EVENTS, Screen.EVENTS, R.string.events_title, C.Tag.events_screen)

    // Features opened from the Overview shortcuts
    placeholderGraph(
        Route.SYMPTOMS,
        Screen.SYMPTOMS,
        R.string.symptoms_title,
        C.Tag.symptoms_screen,
    )
    placeholderGraph(
        Route.PRESCRIPTIONS,
        Screen.PRESCRIPTIONS,
        R.string.prescriptions_title,
        C.Tag.prescriptions_screen,
    )
    placeholderGraph(
        Route.CARE_CIRCLE,
        Screen.CARE_CIRCLE,
        R.string.care_circle_title,
        C.Tag.care_circle_screen,
    )
    placeholderGraph(Route.PROFILE, Screen.PROFILE, R.string.profile_title, C.Tag.profile_screen)
  }
}

/**
 * A feature graph with a single [PlaceholderScreen]. The feature's owner replaces this call with
 * their own `navigation(...)` block when they implement the real screens.
 */
private fun NavGraphBuilder.placeholderGraph(
    route: String,
    screen: String,
    @StringRes title: Int,
    testTag: String,
) {
  navigation(startDestination = screen, route = route) {
    composable(screen) { PlaceholderScreen(title = stringResource(title), testTag = testTag) }
  }
}
