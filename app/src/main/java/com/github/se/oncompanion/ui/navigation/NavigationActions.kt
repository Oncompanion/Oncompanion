package com.github.se.oncompanion.ui.navigation

import androidx.navigation.NavHostController

/**
 * Top-level navigation graphs. Navigating to a route opens the graph at its start screen.
 *
 * To add a feature: add a route here, its screens in [Screen], and a `navigation(...)` block in
 * [AppNavHost].
 */
object Route {
  const val AUTH = "auth"
  const val ONBOARDING = "onboarding"
  const val OVERVIEW = "overview"
}

/** Individual screens, grouped by the [Route] they belong to. */
object Screen {
  // Route.AUTH
  const val SIGN_IN = "sign_in"

  // Route.ONBOARDING
  const val ONBOARDING_ROLE = "onboarding_role"
  const val ONBOARDING_INFORMATION = "onboarding_information"

  // Route.OVERVIEW
  const val OVERVIEW = "overview_home"
}

/** Navigation helpers shared by all screens, so screens never use the NavController directly. */
open class NavigationActions(private val navController: NavHostController) {

  /** Opens [screen] on top of the current one. Back returns to the current screen. */
  open fun navigateTo(screen: String) {
    navController.navigate(screen) { launchSingleTop = true }
  }

  /**
   * Opens [route] and clears the whole back stack, so Back can't return to the previous flow (e.g.
   * after signing in or finishing onboarding). Not for switching between bottom-bar tabs, which
   * should keep each tab's state.
   */
  open fun navigateAndClearBackStack(route: String) {
    navController.navigate(route) {
      popUpTo(navController.graph.id) { inclusive = true }
      launchSingleTop = true
    }
  }

  /**
   * Returns to the previous screen. Does nothing on the first screen of the back stack, so the
   * NavHost is never left empty (blank screen).
   */
  open fun goBack() {
    if (navController.previousBackStackEntry != null) navController.popBackStack()
  }

  /** The current screen's [Screen] constant, or an empty string before the graph is set. */
  open fun currentRoute(): String = navController.currentDestination?.route ?: ""
}
