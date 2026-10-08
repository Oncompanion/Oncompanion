package com.github.se.oncompanion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
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
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.AfterSignIn
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.SignInScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberViewModel
import com.github.se.oncompanion.ui.auth.rememberGoogleCredentialProvider
import com.github.se.oncompanion.ui.carecircle.CareCircleScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleViewModel
import com.github.se.oncompanion.ui.common.PlaceholderScreen
import com.github.se.oncompanion.ui.events.EventDetailScreen
import com.github.se.oncompanion.ui.events.EventsScreen
import com.github.se.oncompanion.ui.onboarding.InformationScreen
import com.github.se.oncompanion.ui.onboarding.OnboardingViewModel
import com.github.se.oncompanion.ui.onboarding.RoleScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen
import com.github.se.oncompanion.ui.planning.PlanningScreen
import com.github.se.oncompanion.ui.planning.PlanningViewModel
import com.github.se.oncompanion.ui.prescription.PrescriptionFormScreen
import com.github.se.oncompanion.ui.prescription.PrescriptionFormViewModel
import com.github.se.oncompanion.ui.profile.EditProfileScreen
import com.github.se.oncompanion.ui.profile.EditProfileViewModel
import com.github.se.oncompanion.ui.profile.ProfileScreen
import com.github.se.oncompanion.ui.profile.ProfileViewModel
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
    profileRepository: UserProfileRepository = UserProfileRepositoryFirestore(),
    credentialProvider: GoogleCredentialProvider = rememberGoogleCredentialProvider(),
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
    navigation(startDestination = Screen.EVENTS, route = Route.EVENTS) {
      composable(Screen.EVENTS) { EventsScreen(navigationActions) }
      composable(
          Screen.EVENT_DETAIL,
          arguments = listOf(navArgument(Screen.EVENT_ID) { type = NavType.StringType }),
      ) { entry ->
        EventDetailScreen(
            eventId = entry.arguments?.getString(Screen.EVENT_ID).orEmpty(),
            onBack = navigationActions::goBack,
        )
      }
    }

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
    navigation(startDestination = Screen.PRESCRIPTIONS, route = Route.PRESCRIPTIONS) {
      // Placeholder until the prescriptions list is implemented
      composable(Screen.PRESCRIPTIONS) {
        PlaceholderScreen(
            title = stringResource(R.string.prescriptions_title),
            testTag = C.Tag.prescriptions_screen,
        )
      }
      composable(Screen.PRESCRIPTION_ADD) {
        PrescriptionFormScreen(
            viewModel = viewModel { PrescriptionFormViewModel() },
            // A second tap while the form is already leaving is dropped, so Back happens once
            onClose = dropUnlessResumed { navigationActions.goBack() },
            // Saving returns to the screen the form was opened from
            onSaved = navigationActions::goBack,
        )
      }
    }
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
            onSignedOut = { navigationActions.navigateAndClearBackStack(Route.AUTH) },
            viewModel = viewModel { ProfileViewModel(authRepository, profileRepository) },
            credentialProvider = credentialProvider,
        )
      }
      composable(Screen.EDIT_PROFILE) {
        EditProfileScreen(
            onBack = navigationActions::goBack,
            onSaved = navigationActions::goBack,
            viewModel = viewModel { EditProfileViewModel(authRepository, profileRepository) },
        )
      }
    }
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
