// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.planning.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var navController: TestNavHostController

  /** The activity showing the NavHost, to answer the permission dialogs its screens open. */
  private lateinit var activity: Activity

  private val context: Context = ApplicationProvider.getApplicationContext()

  // The app's repositories, put back after each test
  private val appPlanningRepository = PlanningRepositoryProvider.repository
  private val appCareCircleRepository = CareCircleRepositoryProvider.repository
  private val appEventRepository = EventRepositoryProvider.repository
  private val appAuthRepository = AuthRepositoryProvider.repository
  private val appAppointmentRepository = AppointmentRepositoryProvider.repository
  private val appMedicationRepository = MedicationRepositoryProvider.repository

  @After
  fun restoreProviders() {
    PlanningRepositoryProvider.repository = appPlanningRepository
    CareCircleRepositoryProvider.repository = appCareCircleRepository
    EventRepositoryProvider.repository = appEventRepository
    AuthRepositoryProvider.repository = appAuthRepository
    AppointmentRepositoryProvider.repository = appAppointmentRepository
    MedicationRepositoryProvider.repository = appMedicationRepository
  }

  /** Shows the NavHost with these repositories in the providers its ViewModels read. */
  private fun setNavHost(
      startRoute: String? = null,
      planningRepository: PlanningRepository = EmptyPlanningRepository,
      careCircleRepository: CareCircleRepository = FakeCareCircleRepository(),
      eventRepository: EventRepository = FakeEventRepository(),
      authRepository: AuthRepository = FakeAuthRepository(),
      medicationRepository: MedicationRepository = FakeMedicationRepository(),
  ) {
    PlanningRepositoryProvider.repository = planningRepository
    CareCircleRepositoryProvider.repository = careCircleRepository
    EventRepositoryProvider.repository = eventRepository
    AuthRepositoryProvider.repository = authRepository
    MedicationRepositoryProvider.repository = medicationRepository
    composeTestRule.setContent {
      activity = checkNotNull(LocalActivity.current)
      navController =
          TestNavHostController(LocalContext.current).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
          }
      if (startRoute == null) AppNavHost(navController = navController)
      else AppNavHost(navController = navController, startRoute = startRoute)
    }
  }

  @Test
  fun defaultStartRoute_displaysSignInScreen() {
    setNavHost()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(context.getString(R.string.sign_in_welcome)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, navController.currentDestination?.route)
    }
  }

  @Test
  fun authStartRoute_displaysSignInScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun onboardingStartRoute_displaysOnboardingRoleScreen() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.onboarding_role_title))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_ROLE, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun overviewStartRoute_displaysOverviewScreen() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    // The default ViewModel works without Firebase: a greeting and the empty day
    composeTestRule.onNodeWithTag(C.Tag.overview_greeting).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_no_appointment).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.OVERVIEW, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun onboardingRoleScreen_patientIsSelectedByDefault_andCaregiverIsDisabled() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
  }

  @Test
  fun onboardingRoleContinue_opensInformationScreen_andBackReturnsToRoleScreen() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_INFORMATION, NavigationActions(navController).currentRoute())
    }

    composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_ROLE, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun onboardingInformationScreen_displaysItsTagAndTitle() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navController.navigate(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.onboarding_information_title))
        .assertIsDisplayed()
  }

  @Test
  fun onboardingInformationScreen_showsTheFormWithTheDefaultViewModel() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).assertExists()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_family_name_field).assertExists()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertExists()
    // No Firebase user in tests: nothing is pre-filled, so the first name is still missing
    composeTestRule.onNodeWithTag(C.Tag.onboarding_save_button).assertIsNotEnabled()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).performTextInput("Sam")
    composeTestRule.onNodeWithTag(C.Tag.onboarding_save_button).assertIsEnabled()
  }

  @Test
  fun onboardingSteps_shareTheViewModel_soInformationIsKeptWhenGoingBackAndForth() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).performTextInput("Sam")

    composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).assertTextContains("Sam")
  }

  /** A feature destination and the title its screen displays. */
  private data class Destination(
      val route: String,
      val screen: String,
      val testTag: String,
      val titleRes: Int,
  )

  private val featureDestinations =
      listOf(
          Destination(
              Route.PLANNING,
              Screen.PLANNING,
              C.Tag.planning_screen,
              R.string.planning_title,
          ),
          Destination(
              Route.EVENTS,
              Screen.EVENTS,
              C.Tag.events_screen,
              R.string.events_screen_title,
          ),
          Destination(
              Route.SYMPTOMS,
              Screen.SYMPTOMS,
              C.Tag.symptoms_screen,
              R.string.symptoms_title,
          ),
          Destination(
              Route.PRESCRIPTIONS,
              Screen.PRESCRIPTIONS,
              C.Tag.prescriptions_screen,
              R.string.prescriptions_list_title,
          ),
          Destination(
              Route.CARE_CIRCLE,
              Screen.CARE_CIRCLE,
              C.Tag.care_circle_screen,
              R.string.care_circle_title,
          ),
          Destination(
              Route.PROFILE,
              Screen.PROFILE,
              C.Tag.profile_screen,
              R.string.profile_screen_title,
          ),
      )

  @Test
  fun everyFeatureRoute_opensItsScreenFromOverview_andBackReturnsToOverview() {
    setNavHost(Route.OVERVIEW)
    val navigationActions = NavigationActions(navController)

    featureDestinations.forEach { destination ->
      composeTestRule.runOnIdle { navigationActions.navigateTo(destination.route) }
      composeTestRule.onNodeWithTag(destination.testTag).assertIsDisplayed()
      // Tabs also show their label in the bottom bar, so look for the title inside the screen
      composeTestRule
          .onNode(
              hasText(context.getString(destination.titleRes)) and
                  hasAnyAncestor(hasTestTag(destination.testTag)) and
                  !hasAnyAncestor(hasTestTag(C.Tag.bottom_navigation_bar))
          )
          .assertIsDisplayed()
      composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
      composeTestRule.runOnIdle {
        assertEquals(destination.screen, navigationActions.currentRoute())
      }

      composeTestRule.runOnIdle { navigationActions.goBack() }
      composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
      composeTestRule.onNodeWithTag(destination.testTag).assertDoesNotExist()
    }
  }

  @Test
  fun overviewShortcuts_openTheirSection_andBackReturnsToOverview() {
    setNavHost(Route.OVERVIEW)

    OverviewShortcut.entries.forEach { shortcut ->
      val section = featureDestinations.single { it.route == shortcut.route }

      val button = composeTestRule.onNodeWithTag(shortcut.testTag)
      // The profile button is in the top bar, which doesn't scroll
      if (shortcut != OverviewShortcut.PROFILE) button.performScrollTo()
      button.performClick()
      composeTestRule.onNodeWithTag(section.testTag).assertIsDisplayed()
      composeTestRule.runOnIdle {
        assertEquals(section.screen, NavigationActions(navController).currentRoute())
      }

      composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
      composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    }
  }

  @Test
  fun profileBackArrow_returnsToOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.onNodeWithTag(OverviewShortcut.PROFILE.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()

    composeTestRule.onNodeWithTag(C.Tag.profile_back).performClick()

    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.OVERVIEW, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun careCircleMember_opensFromTheCircle_andBackReturnsToIt() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val careCircle = FakeCareCircleRepository()
    careCircle.setMembers(
        "owner",
        listOf(
            CareCircleMember(uid = "sophie", firstName = "Sophie", familyName = "Dubois"),
            CareCircleMember(
                uid = "marc",
                firstName = "Marc",
                familyName = "Dubois",
                relationship = Relationship.SON,
            ),
        ),
    )
    setNavHost(Route.CARE_CIRCLE, careCircleRepository = careCircle, authRepository = auth)

    // The route's argument must reach the destination's ViewModel: it shows Marc, not an error
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember("marc")).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertTextEquals("Marc Dubois")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_error).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.CARE_CIRCLE_MEMBER, NavigationActions(navController).currentRoute())
      assertEquals(
          "marc",
          navController.currentBackStackEntry?.arguments?.getString(Screen.CARE_CIRCLE_MEMBER_ARG),
      )
    }

    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_back_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_screen).assertDoesNotExist()
  }

  private fun assertOnTab(tab: Tab, screenTag: String) {
    composeTestRule.onNodeWithTag(screenTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(tab.testTag).assertIsSelected()
    Tab.entries
        .filter { it != tab }
        .forEach { composeTestRule.onNodeWithTag(it.testTag).assertIsNotSelected() }
  }

  @Test
  fun bottomBar_switchesBetweenTheThreeTabs_andHighlightsTheCurrentOne() {
    setNavHost(Route.OVERVIEW)
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)

    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    assertOnTab(Tab.PLANNING, C.Tag.planning_screen)

    composeTestRule.onNodeWithTag(Tab.EVENTS.testTag).performClick()
    assertOnTab(Tab.EVENTS, C.Tag.events_screen)

    composeTestRule.onNodeWithTag(Tab.OVERVIEW.testTag).performClick()
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
  }

  @Test
  fun bottomBar_backFromPlanningOrEvents_returnsToOverview() {
    setNavHost(Route.OVERVIEW)

    listOf(Tab.PLANNING to C.Tag.planning_screen, Tab.EVENTS to C.Tag.events_screen).forEach {
        (tab, screenTag) ->
      composeTestRule.onNodeWithTag(tab.testTag).performClick()
      composeTestRule.onNodeWithTag(screenTag).assertIsDisplayed()

      composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
      assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
    }
  }

  @Test
  fun bottomBar_isNotShownOnSections() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertIsDisplayed()

    composeTestRule
        .onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag)
        .performScrollTo()
        .performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertDoesNotExist()
  }

  @Test
  fun planningTabOpensRealCalendarAndRetainsSelectionBetweenTabs() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.planning_week).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.planning_empty).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.planning_next).performClick()
    val heading =
        composeTestRule
            .onNodeWithTag(C.Tag.planning_heading)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
    composeTestRule.onNodeWithTag(Tab.EVENTS.testTag).performClick()
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.planning_heading).assertTextContains(heading.single().text)
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).assertIsSelected()
  }

  @Test
  fun overviewPlanningLinksOpenRealCalendar() {
    setNavHost(Route.OVERVIEW)
    listOf(C.Tag.overview_no_appointment, C.Tag.overview_see_planning).forEach { tag ->
      composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
      composeTestRule.onNodeWithTag(C.Tag.planning_week).assertIsDisplayed()
      composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).assertIsSelected()
      composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
    }
  }

  @Test
  fun planningAgendaKeepsScrollAndSingleObservationAcrossTabs() {
    val repository = FakePlanningRepository()
    val instant = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    val entries =
        (0 until 30).map {
          PlanningItem(
              PlanningSource.Appointment("item-$it"),
              PlanningTiming.Timed(instant.plusSeconds(it.toLong())),
              "Appointment $it",
          )
        }
    repository.items.value = entries
    setNavHost(Route.OVERVIEW, repository)
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(entries.last().key)))
    composeTestRule.onNodeWithTag(Tab.EVENTS.testTag).performClick()
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.planningItem(entries.last().key)).assertIsDisplayed()
    composeTestRule.runOnIdle { assertEquals(1, repository.ranges.size) }
  }

  @Test
  fun symptomsShortcut_opensTheJournal_andBackReturnsToOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule
        .onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag)
        .performScrollTo()
        .performClick()

    // Without Firebase nobody is signed in: the journal shows its error state
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_error).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOMS, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
  }

  @Test
  fun symptomDetailRoute_opensTheDetailOfThatSymptom_andBackReturns() {
    setNavHost(Route.SYMPTOMS)
    composeTestRule.runOnIdle {
      NavigationActions(navController).navigateTo(Screen.symptomDetail("abc"))
    }

    // Without Firebase nobody is signed in: the detail shows its error state
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_error).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOM_DETAIL, NavigationActions(navController).currentRoute())
      assertEquals(
          "abc",
          navController.currentBackStackEntry?.arguments?.getString(Screen.SYMPTOM_DETAIL_ID),
      )
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
  }

  /** Opens the prescriptions list from its Overview shortcut, as the user does. */
  private fun openPrescriptionsFromOverview(medicationRepository: MedicationRepository) {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    setNavHost(Route.OVERVIEW, authRepository = auth, medicationRepository = medicationRepository)
    composeTestRule
        .onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag)
        .performScrollTo()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
  }

  @Test
  fun prescriptionsShortcut_opensTheList_andBackArrowReturnsToOverview() {
    openPrescriptionsFromOverview(FakeMedicationRepository())

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTIONS, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_back_button).performClick()
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
  }

  @Test
  fun prescriptionsEnterManually_opensTheForm_andCloseReturnsToTheList() {
    openPrescriptionsFromOverview(FakeMedicationRepository())

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_enter_manually_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.prescription_form_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTION_ADD, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.prescription_form_close_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_empty).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTIONS, NavigationActions(navController).currentRoute())
    }
  }

  /** Refuses the camera in Android's permission dialog, which the scan screen opened. */
  private fun refuseCamera() {
    // The scan screen asks once it is shown
    composeTestRule.waitForIdle()
    val request = shadowOf(activity).lastRequestedPermission
    assertNotNull("the scan screen should have asked for the camera", request)
    composeTestRule.runOnUiThread {
      @Suppress("DEPRECATION")
      activity.onRequestPermissionsResult(
          request!!.requestCode,
          request.requestedPermissions,
          intArrayOf(PackageManager.PERMISSION_DENIED),
      )
    }
    composeTestRule.waitForIdle()
  }

  @Test
  fun prescriptionsScan_opensTheScanScreen_andCloseReturnsToTheList() {
    openPrescriptionsFromOverview(FakeMedicationRepository())

    composeTestRule.onNodeWithTag(C.Tag.prescriptions_scan_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTION_SCAN, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_close_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTIONS, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun scanEnterManually_opensTheForm_andCloseReturnsToTheList() {
    openPrescriptionsFromOverview(FakeMedicationRepository())
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_scan_button).performClick()
    refuseCamera()

    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_enter_manually_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.prescription_form_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTION_ADD, NavigationActions(navController).currentRoute())
    }

    // The form replaced the scan screen: leaving it doesn't return to "Camera access is off"
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_close_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_scan_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTIONS, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun savingAPrescription_returnsToTheList_whichShowsIt() {
    val medications = FakeMedicationRepository()
    openPrescriptionsFromOverview(medications)
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_enter_manually_button).performClick()

    composeTestRule
        .onNodeWithTag(C.Tag.prescription_form_medication_name_field)
        .performScrollTo()
        .performTextInput("Ondansetron 8 mg")
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_save_button).performClick()

    // The form and the list share the repository: the list shows what was just saved
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.prescription_form_screen).assertDoesNotExist()
    val saved = medications.prescriptionsOf("owner").single()
    composeTestRule.onNodeWithTag(C.Tag.prescriptionsItem(saved.id)).assertIsDisplayed()
    composeTestRule
        .onNode(
            hasText("Ondansetron 8 mg") and
                hasAnyAncestor(hasTestTag(C.Tag.prescriptionsItem(saved.id))),
            useUnmergedTree = true,
        )
        .assertExists()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PRESCRIPTIONS, NavigationActions(navController).currentRoute())
    }

    // Back from the list returns to Overview, not to the form
    composeTestRule.onNodeWithTag(C.Tag.prescriptions_back_button).performClick()
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
  }

  @Test
  fun routeAndScreenConstants_haveExpectedValues() {
    assertEquals("auth", Route.AUTH)
    assertEquals("onboarding", Route.ONBOARDING)
    assertEquals("overview", Route.OVERVIEW)
    assertEquals("prescriptions_add", Screen.PRESCRIPTION_ADD)
    assertEquals("prescriptions_scan", Screen.PRESCRIPTION_SCAN)
    assertEquals("sign_in", Screen.SIGN_IN)
    assertEquals("onboarding_role", Screen.ONBOARDING_ROLE)
    assertEquals("onboarding_information", Screen.ONBOARDING_INFORMATION)
    assertEquals("overview_home", Screen.OVERVIEW)
  }

  @Test
  fun editProfile_isDistinctDestination_andBackReturnsThroughProfileToOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.onNodeWithTag(OverviewShortcut.PROFILE.testTag).performClick()
    composeTestRule.runOnIdle { NavigationActions(navController).navigateTo(Screen.EDIT_PROFILE) }
    composeTestRule.onNodeWithTag(C.Tag.edit_profile_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.EDIT_PROFILE, navController.currentDestination?.route)
    }
    composeTestRule.onNodeWithTag(C.Tag.edit_profile_back).performClick()
    composeTestRule.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_back).performClick()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun eventCard_opensItsDetail_andBackReturnsToTheList() {
    setNavHost(Route.EVENTS)
    val first = FakeEventRepository.sampleEvents(LocalDate.now()).first()

    composeTestRule.onNodeWithTag(C.Tag.eventCard(first.id)).performClick()

    composeTestRule.onNodeWithTag(C.Tag.event_detail_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_event_title)
        .assertIsDisplayed()
        .assertTextEquals(first.title)
    composeTestRule.runOnIdle {
      assertEquals(Screen.EVENT_DETAIL, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.event_detail_back).performClick()

    composeTestRule.onNodeWithTag(C.Tag.events_list).assertIsDisplayed()
  }

  /** Verifies the default Planning source reads the appointment, auth and event providers. */
  @Test
  fun defaultPlanningSourceShowsAppointmentsAndEvents() {
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("planning-owner") }
    val appointments = FakeAppointmentRepository()
    appointments.seed(
        auth.currentUser!!.uid,
        Appointment(
            id = "saved",
            title = "Saved consultation",
            scheduledAt = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
            type = AppointmentType.CONSULTATION,
        ),
    )
    AppointmentRepositoryProvider.repository = appointments
    // An event later today: the fake's "now" is midnight, so it counts as upcoming
    val today = LocalDate.now()
    val events =
        FakeEventRepository(
            listOf(
                FakeEventRepository.sampleEvents(today)
                    .first()
                    .copy(startDateTime = today.atTime(15, 0))
            ),
            now = { today.atStartOfDay() },
        )
    // The planning provider keeps its default, so the entries can only come through it
    setNavHost(
        Route.PLANNING,
        planningRepository = appPlanningRepository,
        authRepository = auth,
        eventRepository = events,
    )
    composeTestRule.onNodeWithText("Saved consultation").assertIsDisplayed()
    composeTestRule.onNodeWithText("Gentle yoga for patients").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
  }
}
