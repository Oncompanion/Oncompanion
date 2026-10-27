// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.github.se.oncompanion.model.medication.scan.ScanPhotoStore
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.ui.appointment.AppointmentDetailScreen
import com.github.se.oncompanion.ui.appointment.AppointmentDetailViewModel
import com.github.se.oncompanion.ui.appointment.AppointmentFormScreen
import com.github.se.oncompanion.ui.appointment.AppointmentFormViewModel
import com.github.se.oncompanion.ui.auth.AfterSignIn
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.SignInScreen
import com.github.se.oncompanion.ui.auth.rememberGoogleCredentialProvider
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberFormScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberFormViewModel
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleMemberViewModel
import com.github.se.oncompanion.ui.carecircle.CareCircleScreen
import com.github.se.oncompanion.ui.carecircle.CareCircleViewModel
import com.github.se.oncompanion.ui.carecircle.JoinCareCircleScreen
import com.github.se.oncompanion.ui.carecircle.JoinCareCircleViewModel
import com.github.se.oncompanion.ui.carecircle.WhoseCareScreen
import com.github.se.oncompanion.ui.carecircle.WhoseCareViewModel
import com.github.se.oncompanion.ui.events.EventDetailScreen
import com.github.se.oncompanion.ui.events.EventDetailViewModel
import com.github.se.oncompanion.ui.events.EventsScreen
import com.github.se.oncompanion.ui.events.EventsViewModel
import com.github.se.oncompanion.ui.onboarding.InformationScreen
import com.github.se.oncompanion.ui.onboarding.OnboardingViewModel
import com.github.se.oncompanion.ui.onboarding.RoleScreen
import com.github.se.oncompanion.ui.overview.OverviewScreen
import com.github.se.oncompanion.ui.planning.PlanningScreen
import com.github.se.oncompanion.ui.planning.PlanningViewModel
import com.github.se.oncompanion.ui.prescription.PrescriptionDetailScreen
import com.github.se.oncompanion.ui.prescription.PrescriptionDetailViewModel
import com.github.se.oncompanion.ui.prescription.PrescriptionFormScreen
import com.github.se.oncompanion.ui.prescription.PrescriptionFormViewModel
import com.github.se.oncompanion.ui.prescription.PrescriptionsScreen
import com.github.se.oncompanion.ui.prescription.PrescriptionsViewModel
import com.github.se.oncompanion.ui.prescription.scan.ScanHandoffViewModel
import com.github.se.oncompanion.ui.prescription.scan.ScanScreen
import com.github.se.oncompanion.ui.profile.EditProfileScreen
import com.github.se.oncompanion.ui.profile.EditProfileViewModel
import com.github.se.oncompanion.ui.profile.ProfileScreen
import com.github.se.oncompanion.ui.profile.ProfileViewModel
import com.github.se.oncompanion.ui.symptom.AddSymptomScreen
import com.github.se.oncompanion.ui.symptom.AddSymptomViewModel
import com.github.se.oncompanion.ui.symptom.SymptomDetailScreen
import com.github.se.oncompanion.ui.symptom.SymptomDetailViewModel
import com.github.se.oncompanion.ui.symptom.SymptomJournalScreen
import java.time.LocalDate

/**
 * The app's navigation graph. Each feature lives in its own nested graph ([Route]).
 *
 * Every feature added with [placeholderGraph] is a placeholder until its PR lands.
 *
 * The ViewModels take their repositories from the providers; tests put fakes there to check the
 * screens together, e.g. that a route's argument reaches its ViewModel.
 *
 * @param credentialProvider gets the Google ID token, for signing in and out; end-to-end tests pass
 *   one that signs in against the Auth emulator, as Google's account picker can't be driven
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startRoute: String = Route.AUTH,
    credentialProvider: GoogleCredentialProvider = rememberGoogleCredentialProvider(),
) {
  val navigationActions = remember(navController) { NavigationActions(navController) }

  NavHost(navController = navController, startDestination = startRoute) {
    navigation(startDestination = Screen.SIGN_IN, route = Route.AUTH) {
      composable(Screen.SIGN_IN) {
        SignInScreen(
            credentialProvider = credentialProvider,
            onSignedIn = { next ->
              navigationActions.navigateAndClearBackStack(
                  when (next) {
                    AfterSignIn.ONBOARDING -> Route.ONBOARDING
                    AfterSignIn.OVERVIEW -> Route.OVERVIEW
                    AfterSignIn.JOIN_CIRCLE -> Route.JOIN_CIRCLE
                    AfterSignIn.WHOSE_CARE -> Route.WHOSE_CARE
                  }
              )
            },
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
            // Onboarding is done: Back must not return to it. A caregiver then joins a circle
            onSaved = { role ->
              navigationActions.navigateAndClearBackStack(
                  if (role == Role.CAREGIVER) Route.JOIN_CIRCLE else Route.OVERVIEW
              )
            },
        )
      }
    }

    navigation(startDestination = Screen.JOIN_CIRCLE, route = Route.JOIN_CIRCLE) {
      composable(Screen.JOIN_CIRCLE) {
        JoinCareCircleScreen(
            viewModel = viewModel { JoinCareCircleViewModel() },
            // Joined: choose whose care to follow (straight to them with a single patient)
            onDone = { navigationActions.navigateAndClearBackStack(Route.WHOSE_CARE) },
            onOpenProfile = { navigationActions.navigateTo(Screen.PROFILE) },
            onSignedOut = { navigationActions.navigateAndClearBackStack(Route.AUTH) },
        )
      }
    }

    navigation(startDestination = Screen.WHOSE_CARE, route = Route.WHOSE_CARE) {
      composable(
          Screen.WHOSE_CARE,
          arguments =
              listOf(
                  navArgument(Screen.WHOSE_CARE_AUTO_SELECT) {
                    type = NavType.BoolType
                    defaultValue = true
                  }
              ),
      ) { entry ->
        WhoseCareScreen(
            viewModel = viewModel { WhoseCareViewModel() },
            // No back arrow when it is the first screen (after joining, or at start)
            onBack =
                navigationActions::goBack.takeIf { navController.previousBackStackEntry != null },
            // The Overview for now; the caregiver mode (#203) opens the chosen patient's one
            onPatientChosen = { navigationActions.navigateAndClearBackStack(Route.OVERVIEW) },
            onJoinAnother = { navigationActions.navigateTo(Screen.JOIN_CIRCLE) },
            autoSelectSingle = entry.arguments?.getBoolean(Screen.WHOSE_CARE_AUTO_SELECT) ?: true,
            onSignedOut = { navigationActions.navigateAndClearBackStack(Route.AUTH) },
        )
      }
    }

    navigation(startDestination = Screen.OVERVIEW, route = Route.OVERVIEW) {
      composable(Screen.OVERVIEW) {
        OverviewScreen(navigationActions, onOpenAppointment = appointmentOpener(navigationActions))
      }
    }

    // Bottom bar tabs, next to Overview
    navigation(startDestination = Screen.PLANNING, route = Route.PLANNING) {
      composable(Screen.PLANNING) { entry ->
        val planningViewModel: PlanningViewModel = viewModel {
          PlanningViewModel(savedStateHandle = createSavedStateHandle())
        }
        // Return to this calendar instance and reveal the saved day, including another week.
        val savedDate by
            entry.savedStateHandle
                .getStateFlow<String?>(Screen.APPOINTMENT_SAVED_DATE, null)
                .collectAsStateWithLifecycle()
        LaunchedEffect(savedDate) {
          savedDate?.let { date ->
            planningViewModel.selectDate(LocalDate.parse(date))
            entry.savedStateHandle[Screen.APPOINTMENT_SAVED_DATE] = null
          }
        }
        PlanningScreen(
            navigationActions,
            planningViewModel,
            onAddAppointment = { navigationActions.navigateTo(Screen.appointmentAdd(it)) },
            onOpenAppointment = appointmentOpener(navigationActions),
        )
      }
      composable(
          Screen.APPOINTMENT_DETAIL,
          arguments = listOf(navArgument(Screen.APPOINTMENT_ID) { type = NavType.StringType }),
      ) { entry ->
        val id = requireNotNull(entry.arguments?.getString(Screen.APPOINTMENT_ID))
        val detail: AppointmentDetailViewModel = viewModel { AppointmentDetailViewModel(id) }
        AppointmentDetailScreen(
            detail,
            onBack = dropUnlessResumed { navigationActions.goBack() },
            onEdit = dropUnlessResumed { navigationActions.navigateTo(Screen.appointmentEdit(id)) },
        )
      }
      composable(
          Screen.APPOINTMENT_EDIT,
          arguments = listOf(navArgument(Screen.APPOINTMENT_ID) { type = NavType.StringType }),
      ) { entry ->
        val id = requireNotNull(entry.arguments?.getString(Screen.APPOINTMENT_ID))
        val form: AppointmentFormViewModel = viewModel {
          AppointmentFormViewModel(LocalDate.now(), appointmentId = id)
        }
        AppointmentFormScreen(
            form,
            onBack = dropUnlessResumed { navigationActions.goBack() },
            onSaved = {
              if (navController.currentBackStackEntry == entry) navigationActions.goBack()
            },
        )
      }
      composable(
          Screen.APPOINTMENT_ADD,
          arguments =
              listOf(
                  navArgument(Screen.APPOINTMENT_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                  }
              ),
      ) { entry ->
        val date =
            runCatching { LocalDate.parse(entry.arguments?.getString(Screen.APPOINTMENT_DATE)) }
                .getOrNull()
                ?.takeIf { it.year in 1..9999 } ?: LocalDate.now()
        val form: AppointmentFormViewModel = viewModel { AppointmentFormViewModel(date) }
        AppointmentFormScreen(
            form,
            onBack = dropUnlessResumed { navigationActions.goBack() },
            onSaved = { instant ->
              // A completion during an exit animation must not pop the calendar as well.
              if (navController.currentBackStackEntry == entry) {
                navController.previousBackStackEntry
                    ?.savedStateHandle
                    ?.set(
                        Screen.APPOINTMENT_SAVED_DATE,
                        instant.atZone(form.zoneId).toLocalDate().toString(),
                    )
                navigationActions.goBack()
              }
            },
        )
      }
    }
    navigation(startDestination = Screen.EVENTS, route = Route.EVENTS) {
      composable(Screen.EVENTS) {
        EventsScreen(navigationActions, viewModel = viewModel { EventsViewModel() })
      }
      composable(
          Screen.EVENT_DETAIL,
          arguments = listOf(navArgument(Screen.EVENT_ID) { type = NavType.StringType }),
      ) { entry ->
        val eventId = entry.arguments?.getString(Screen.EVENT_ID).orEmpty()
        EventDetailScreen(
            eventId = eventId,
            onBack = navigationActions::goBack,
            viewModel = viewModel { EventDetailViewModel(eventId) },
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
      composable(Screen.SYMPTOM_ADD) {
        // Saving returns to the journal, where the new entry is at the top
        AddSymptomScreen(
            viewModel = viewModel { AddSymptomViewModel() },
            onBack = navigationActions::goBack,
            onSaved = navigationActions::goBack,
        )
      }
      composable(
          Screen.SYMPTOM_EDIT,
          arguments = listOf(navArgument(Screen.SYMPTOM_DETAIL_ID) { type = NavType.StringType }),
      ) { entry ->
        val symptomId = entry.arguments?.getString(Screen.SYMPTOM_DETAIL_ID).orEmpty()
        // Saving returns to the detail, which shows the change
        AddSymptomScreen(
            viewModel = viewModel { AddSymptomViewModel(symptomId = symptomId) },
            onBack = navigationActions::goBack,
            onSaved = navigationActions::goBack,
        )
      }
    }
    navigation(startDestination = Screen.PRESCRIPTIONS, route = Route.PRESCRIPTIONS) {
      composable(Screen.PRESCRIPTIONS) {
        PrescriptionsScreen(
            viewModel = viewModel { PrescriptionsViewModel() },
            onBack = navigationActions::goBack,
            // A second tap while the form is already opening is dropped, so it opens once
            onAdd = dropUnlessResumed { navigationActions.navigateTo(Screen.PRESCRIPTION_ADD) },
            onScan = dropUnlessResumed { navigationActions.navigateTo(Screen.PRESCRIPTION_SCAN) },
            onOpen = { id -> navigationActions.navigateTo(Screen.prescriptionDetail(id)) },
        )
      }
      composable(
          Screen.PRESCRIPTION_DETAIL,
          arguments = listOf(navArgument(Screen.PRESCRIPTION_ID) { type = NavType.StringType }),
      ) { entry ->
        val prescriptionId = entry.arguments?.getString(Screen.PRESCRIPTION_ID).orEmpty()
        PrescriptionDetailScreen(
            // Scoped to this back stack entry, so each opened prescription has its own ViewModel
            viewModel = viewModel { PrescriptionDetailViewModel(prescriptionId) },
            onBack = navigationActions::goBack,
            onEdit = { navigationActions.navigateTo(Screen.prescriptionEdit(prescriptionId)) },
            // Back to the list, which no longer shows it
            onDeleted = navigationActions::goBack,
        )
      }
      composable(
          Screen.PRESCRIPTION_EDIT,
          arguments = listOf(navArgument(Screen.PRESCRIPTION_ID) { type = NavType.StringType }),
      ) { entry ->
        val prescriptionId = entry.arguments?.getString(Screen.PRESCRIPTION_ID).orEmpty()
        PrescriptionFormScreen(
            viewModel = viewModel { PrescriptionFormViewModel(prescriptionId = prescriptionId) },
            // A second tap while the form is already leaving is dropped, so Back happens once
            onClose = dropUnlessResumed { navigationActions.goBack() },
            // Saving returns to the detail, which shows the correction
            onSaved = navigationActions::goBack,
        )
      }
      composable(Screen.PRESCRIPTION_SCAN) { entry ->
        val handoff = scanHandoffViewModel(navController, entry)
        ScanScreen(
            // The form replaces the scan screen, so closing or saving it returns to the list
            onEnterManually =
                dropUnlessResumed {
                  navigationActions.goBack()
                  navigationActions.navigateTo(Screen.PRESCRIPTION_ADD)
                },
            onBack = dropUnlessResumed { navigationActions.goBack() },
            // The form opens with what was read, in place of the scan screen as for manual entry
            onRead = { scan ->
              handoff.hand(scan)
              navigationActions.goBack()
              navigationActions.navigateTo(Screen.PRESCRIPTION_ADD)
            },
        )
      }
      composable(Screen.PRESCRIPTION_ADD) { entry ->
        val handoff = scanHandoffViewModel(navController, entry)
        PrescriptionFormScreen(
            // Starts from the scanned prescription if it was just read, empty otherwise
            viewModel =
                viewModel { PrescriptionFormViewModel(initialDraft = handoff.scanned?.draft) },
            // A second tap while the form is already leaving is dropped, so Back happens once. The
            // scan's photo is no longer needed once the form is done
            onClose =
                dropUnlessResumed {
                  handoff.finish()
                  navigationActions.goBack()
                },
            // Saving returns to the screen the form was opened from
            onSaved = {
              handoff.finish()
              navigationActions.goBack()
            },
        )
      }
    }
    navigation(startDestination = Screen.CARE_CIRCLE, route = Route.CARE_CIRCLE) {
      composable(Screen.CARE_CIRCLE) {
        CareCircleScreen(
            navigationActions = navigationActions,
            viewModel = viewModel { CareCircleViewModel() },
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
            viewModel = viewModel { CareCircleMemberViewModel(createSavedStateHandle()) },
        )
      }
      composable(Screen.CARE_CIRCLE_ADD) {
        CareCircleMemberFormScreen(
            onBack = dropUnlessResumed { navigationActions.goBack() },
            // Saving returns to the list, where the person is shown as waiting to join
            onSaved = { navigationActions.goBack() },
            viewModel = viewModel { CareCircleMemberFormViewModel() },
        )
      }
    }
    navigation(startDestination = Screen.PROFILE, route = Route.PROFILE) {
      composable(Screen.PROFILE) {
        ProfileScreen(
            onBack = navigationActions::goBack,
            onEdit = { navigationActions.navigateTo(Screen.EDIT_PROFILE) },
            onSignedOut = { navigationActions.navigateAndClearBackStack(Route.AUTH) },
            viewModel = viewModel { ProfileViewModel() },
            credentialProvider = credentialProvider,
        )
      }
      composable(Screen.EDIT_PROFILE) {
        EditProfileScreen(
            onBack = navigationActions::goBack,
            onSaved = navigationActions::goBack,
            viewModel = viewModel { EditProfileViewModel() },
        )
      }
    }
  }
}

/**
 * The [ScanHandoffViewModel] shared by the prescriptions screens: it belongs to the prescriptions
 * graph, so it passes the scanned prescription from the scan screen to the form, and is cleared,
 * deleting the photo, when the user leaves the prescriptions screens.
 */
@Composable
private fun scanHandoffViewModel(
    navController: NavHostController,
    entry: NavBackStackEntry,
): ScanHandoffViewModel {
  val prescriptionsGraph = remember(entry) { navController.getBackStackEntry(Route.PRESCRIPTIONS) }
  val cacheDir = LocalContext.current.cacheDir
  return viewModel(viewModelStoreOwner = prescriptionsGraph) {
    ScanHandoffViewModel(ScanPhotoStore(cacheDir))
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

/** Shares the resumed-only guard for appointment navigation from either bottom tab. */
@Composable
private fun appointmentOpener(navigationActions: NavigationActions): (String) -> Unit {
  val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
  return { id ->
    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
      navigationActions.navigateTo(Screen.appointmentDetail(id))
    }
  }
}
