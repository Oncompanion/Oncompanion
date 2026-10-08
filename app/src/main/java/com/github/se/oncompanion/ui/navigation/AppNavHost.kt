package com.github.se.oncompanion.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.planning.EmptyPlanningRepository
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.AfterSignIn
import com.github.se.oncompanion.ui.auth.SignInScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberViewModel
import com.github.se.oncompanion.ui.carecircle.CareCircleScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleViewModel
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.onboarding.InformationScreen
import com.github.se.oncompanion.ui.onboarding.OnboardingViewModel
import com.github.se.oncompanion.ui.onboarding.RoleScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen
import com.github.se.oncompanion.ui.planning.PlanningScreen
import com.github.se.oncompanion.ui.planning.PlanningViewModel
import com.github.se.oncompanion.ui.profile.EditProfileScreen
import com.github.se.oncompanion.ui.profile.ProfileScreen
import com.github.se.oncompanion.ui.symptom.SymptomDetailScreen
import com.github.se.oncompanion.ui.symptom.SymptomDetailViewModel
import com.github.se.oncompanion.ui.symptom.SymptomJournalScreen
import java.time.Clock
import java.time.ZoneId

/**
 * The app's navigation graph. Each feature lives in its own nested graph ([Route]).
 *
 * Every feature added with [placeholderGraph] is a placeholder until its PR lands.
 *
 * The repositories default to Firebase; tests pass fakes to check the screens together, e.g. that a
 * route's argument reaches its ViewModel.
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startRoute: String = Route.AUTH,
    planningRepository: PlanningRepository = EmptyPlanningRepository,
    careCircleRepository: CareCircleRepository = remember { CareCircleRepositoryFirestore() },
    authRepository: AuthRepository = remember { AuthRepositoryFirebase() },
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
    navigation(startDestination = Screen.PLANNING, route = Route.PLANNING) {
      composable(Screen.PLANNING) {
        val planningViewModel: PlanningViewModel = viewModel {
          PlanningViewModel(
              planningRepository,
              Clock.systemDefaultZone(),
              ZoneId.systemDefault(),
              createSavedStateHandle(),
          )
        }
        PlanningScreen(navigationActions, planningViewModel)
      }
    }
    tabPlaceholderGraph(Tab.EVENTS, Screen.EVENTS, C.Tag.events_screen, navigationActions)

    // Features opened from the Overview shortcuts
    navigation(startDestination = Screen.SYMPTOMS, route = Route.SYMPTOMS) {
      composable(Screen.SYMPTOMS) { SymptomJournalScreen(navigationActions) }
      composable(
          Screen.SYMPTOM_DETAIL,
          arguments = listOf(navArgument(Screen.SYMPTOM_DETAIL_ID) { type = NavType.StringType }),
      ) { entry ->
        val symptomId = entry.arguments?.getString(Screen.SYMPTOM_DETAIL_ID).orEmpty()
        SymptomDetailScreen(
            navigationActions = navigationActions,
            // Scoped to this back stack entry, so each opened symptom has its own ViewModel
            viewModel = viewModel { SymptomDetailViewModel(symptomId) },
        )
      }
    }
    placeholderGraph(
        Route.PRESCRIPTIONS,
        Screen.PRESCRIPTIONS,
        R.string.prescriptions_title,
        C.Tag.prescriptions_screen,
    )
    navigation(startDestination = Screen.CARE_CIRCLE, route = Route.CARE_CIRCLE) {
      composable(Screen.CARE_CIRCLE) {
        CareCircleScreen(
            navigationActions = navigationActions,
            viewModel = viewModel { CareCircleViewModel(careCircleRepository, authRepository) },
        )
      }
      composable(
          Screen.CARE_CIRCLE_MEMBER,
          arguments =
              listOf(navArgument(Screen.CARE_CIRCLE_MEMBER_ARG) { type = NavType.StringType }),
      ) {
        CareCircleMemberScreen(
            navigationActions = navigationActions,
            // The SavedStateHandle holds the route's member uid
            viewModel =
                viewModel {
                  CareCircleMemberViewModel(
                      createSavedStateHandle(),
                      careCircleRepository,
                      authRepository,
                  )
                },
        )
      }
    }
    navigation(startDestination = Screen.PROFILE, route = Route.PROFILE) {
      composable(Screen.PROFILE) {
        ProfileScreen(
            onBack = navigationActions::goBack,
            onEdit = { navigationActions.navigateTo(Screen.EDIT_PROFILE) },
        )
      }
      composable(Screen.EDIT_PROFILE) {
        EditProfileScreen(onBack = navigationActions::goBack, onSaved = navigationActions::goBack)
      }
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
