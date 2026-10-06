package com.github.se.oncompanion.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
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
import com.github.se.oncompanion.ui.carecircle.CareCircleScreen
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.onboarding.InformationScreen
import com.github.se.oncompanion.ui.onboarding.OnboardingViewModel
import com.github.se.oncompanion.ui.onboarding.RoleScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen
import com.github.se.oncompanion.ui.profile.ProfileScreen

/**
 * The app's navigation graph. Each feature lives in its own nested graph ([Route]).
 *
 * Every feature added with [placeholderGraph] is a placeholder until its PR lands.
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
      composable(Screen.ONBOARDING_ROLE) { entry ->
        RoleScreen(
            viewModel = onboardingViewModel(navController, entry),
            onContinue = { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) },
        )
      }
      composable(Screen.ONBOARDING_INFORMATION) { entry ->
        InformationScreen(
            viewModel = onboardingViewModel(navController, entry),
            // Onboarding is done: Back must not return to it
            onSaved = { navigationActions.navigateAndClearBackStack(Route.OVERVIEW) },
        )
      }
    }

    navigation(startDestination = Screen.OVERVIEW, route = Route.OVERVIEW) {
      composable(Screen.OVERVIEW) { OverviewScreen(navigationActions) }
    }

    // Bottom bar tabs, next to Overview
    tabPlaceholderGraph(Tab.PLANNING, Screen.PLANNING, C.Tag.planning_screen, navigationActions)
    tabPlaceholderGraph(Tab.EVENTS, Screen.EVENTS, C.Tag.events_screen, navigationActions)

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
    navigation(startDestination = Screen.CARE_CIRCLE, route = Route.CARE_CIRCLE) {
      composable(Screen.CARE_CIRCLE) { CareCircleScreen(navigationActions) }
    }
    navigation(startDestination = Screen.PROFILE, route = Route.PROFILE) {
      composable(Screen.PROFILE) { ProfileScreen(onBack = navigationActions::goBack) }
    }
  }
}

/**
 * Like [placeholderGraph], for a bottom-bar [tab]: the placeholder is shown with the bottom bar,
 * and its title is the tab's label.
 */
private fun NavGraphBuilder.tabPlaceholderGraph(
    tab: Tab,
    screen: String,
    testTag: String,
    navigationActions: NavigationActions,
) {
  navigation(startDestination = screen, route = tab.route) {
    composable(screen) {
      Scaffold(
          bottomBar = {
            BottomNavigationBar(
                selectedTab = tab,
                onTabSelected = { selected -> navigationActions.navigateToTab(selected.route) },
            )
          }
      ) { innerPadding ->
        PlaceholderScreen(
            title = stringResource(tab.label),
            testTag = testTag,
            modifier = Modifier.padding(innerPadding),
        )
      }
    }
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

/**
 * The [OnboardingViewModel] shared by the onboarding screens: it belongs to the onboarding graph,
 * so it keeps the user's answers while they move between the steps.
 */
@Composable
private fun onboardingViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): OnboardingViewModel {
  val onboardingGraph = remember(entry) { navController.getBackStackEntry(Route.ONBOARDING) }
  return viewModel(viewModelStoreOwner = onboardingGraph) { OnboardingViewModel() }
}
