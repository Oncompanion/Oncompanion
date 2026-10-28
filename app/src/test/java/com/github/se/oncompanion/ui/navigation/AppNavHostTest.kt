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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
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
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.planning.*
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import com.github.se.oncompanion.ui.auth.GoogleCredentialProvider
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
  private val appPublicEvents = PlanningRepositoryProvider.publicEvents
  private val appCareCircleRepository = CareCircleRepositoryProvider.repository
  private val appCareCircleInviteRepository = CareCircleInviteRepositoryProvider.repository
  private val appEventRepository = EventRepositoryProvider.repository
  private val appAuthRepository = AuthRepositoryProvider.repository
  private val appAppointmentRepository = AppointmentRepositoryProvider.repository
  private val appMedicationRepository = MedicationRepositoryProvider.repository
  private val appSymptomRepository = SymptomRepositoryProvider.repository
  private val appUserProfileRepository = UserProfileRepositoryProvider.repository

  @After
  fun restoreProviders() {
    PlanningRepositoryProvider.repository = appPlanningRepository
    PlanningRepositoryProvider.publicEvents = appPublicEvents
    CareCircleRepositoryProvider.repository = appCareCircleRepository
    CareCircleInviteRepositoryProvider.repository = appCareCircleInviteRepository
    EventRepositoryProvider.repository = appEventRepository
    AuthRepositoryProvider.repository = appAuthRepository
    AppointmentRepositoryProvider.repository = appAppointmentRepository
    MedicationRepositoryProvider.repository = appMedicationRepository
    SymptomRepositoryProvider.repository = appSymptomRepository
    UserProfileRepositoryProvider.repository = appUserProfileRepository
  }

  /** Shows the NavHost with these repositories in the providers its ViewModels read. */
  private fun setNavHost(
      startRoute: String? = null,
      planningRepository: PlanningRepository = EmptyPlanningRepository,
      publicEvents: PlanningRepository = EmptyPlanningRepository,
      careCircleRepository: CareCircleRepository = FakeCareCircleRepository(),
      eventRepository: EventRepository = FakeEventRepository(),
      careCircleInviteRepository: CareCircleInviteRepository = FakeCareCircleInviteRepository(),
      authRepository: AuthRepository = FakeAuthRepository(),
      medicationRepository: MedicationRepository = FakeMedicationRepository(),
      symptomRepository: SymptomRepository = FakeSymptomRepository(),
      credentialProvider: GoogleCredentialProvider = FakeGoogleCredentialProvider(),
  ) {
    PlanningRepositoryProvider.repository = planningRepository
    PlanningRepositoryProvider.publicEvents = publicEvents
    CareCircleRepositoryProvider.repository = careCircleRepository
    CareCircleInviteRepositoryProvider.repository = careCircleInviteRepository
    EventRepositoryProvider.repository = eventRepository
    AuthRepositoryProvider.repository = authRepository
    MedicationRepositoryProvider.repository = medicationRepository
    SymptomRepositoryProvider.repository = symptomRepository
    composeTestRule.setContent {
      activity = checkNotNull(LocalActivity.current)
      navController =
          TestNavHostController(LocalContext.current).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
          }
      if (startRoute == null) {
        AppNavHost(navController = navController, credentialProvider = credentialProvider)
      } else {
        AppNavHost(
            navController = navController,
            startRoute = startRoute,
            credentialProvider = credentialProvider,
        )
      }
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
  fun onboardingRoleScreen_patientIsSelectedByDefault_andCaregiverCanBeChosen() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsEnabled()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
  }

  @Test
  fun onboardingAsCaregiver_informationScreenHasNoCancerType() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).assertExists()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertDoesNotExist()

    // Going back keeps the choice, and picking patient brings the cancer type back
    composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.cancer_type_field).assertExists()
  }

  @Test
  fun caregiverOnboarding_endsOnJoinACareCircle_whichBackCantLeave() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "caregiver") })
    runBlocking { auth.signInWithGoogle("token") }
    val profiles = FakeUserProfileRepository()
    UserProfileRepositoryProvider.repository = profiles
    setNavHost(Route.ONBOARDING, authRepository = auth)

    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field).performTextInput("Sam")
    composeTestRule.onNodeWithTag(C.Tag.onboarding_save_button).performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.join_circle_screen).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.runOnIdle {
      assertEquals(Role.CAREGIVER, profiles.profiles["caregiver"]?.role)
      assertEquals(Screen.JOIN_CIRCLE, NavigationActions(navController).currentRoute())
      // Onboarding is done: Back doesn't return to it
      NavigationActions(navController).goBack()
    }
    composeTestRule.onNodeWithTag(C.Tag.join_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
  }

  @Test
  fun joinACareCircle_withAValidCode_joinsThenOpensTheirCare() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "caregiver") })
    runBlocking { auth.signInWithGoogle("token") }
    // Joining adds the caregiver to Marie's circle, which "Whose care?" then reads
    val circle = FakeCareCircleRepository()
    val invites = FakeCareCircleInviteRepository(circle)
    invites.setInvites(
        listOf(
            CareCircleInvite(
                inviteCode = "ABCDEFGHJK",
                ownerUid = "patient",
                ownerFirstName = "Marie",
                details = CareCircleMemberDetails(firstName = "Sam"),
            )
        )
    )
    setNavHost(
        Route.JOIN_CIRCLE,
        careCircleRepository = circle,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )

    composeTestRule.onNodeWithTag(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    composeTestRule.onNodeWithTag(C.Tag.join_circle_join_button).performScrollTo().performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.join_circle_joined).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.join_circle_continue).performScrollTo().performClick()

    // "Whose care?" chooses Marie by itself, the only patient, and opens her Overview
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.overview_screen).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertTextContains(context.getString(R.string.overview_care_of, "Marie"))
    composeTestRule.runOnIdle {
      assertEquals("patient", invites.joinedCircles("caregiver").single().patientUid)
    }
  }

  /** A signed-in caregiver following [patients] (uid to first name), for "Whose care?". */
  private fun followingCaregiver(
      vararg patients: Pair<String, String>
  ): Triple<FakeAuthRepository, FakeCareCircleInviteRepository, FakeCareCircleRepository> {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "caregiver") })
    runBlocking { auth.signInWithGoogle("token") }
    val invites = FakeCareCircleInviteRepository()
    val circles = FakeCareCircleRepository()
    for ((uid, _) in patients) {
      circles.setMembers(uid, listOf(CareCircleMember(uid = "caregiver", firstName = "Sam")))
    }
    invites.setJoinedCircles(
        "caregiver",
        patients.map { (uid, name) -> JoinedCareCircle(uid, name) },
    )
    return Triple(auth, invites, circles)
  }

  @Test
  fun whoseCare_withSeveralPatients_listsThem_andChoosingOneOpensTheirOverview() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne", "p-paul" to "Paul")
    setNavHost(
        Route.WHOSE_CARE,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.whoseCarePatient("p-paul"))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    // The first screen: no back arrow
    composeTestRule.onNodeWithTag(C.Tag.whose_care_back).assertDoesNotExist()

    composeTestRule.onNodeWithTag(C.Tag.whoseCarePatient("p-paul")).performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertTextContains(context.getString(R.string.overview_care_of, "Paul"))
  }

  @Test
  fun caregiverMode_titleSwitchesPatient_andPlanningCantAdd() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne", "p-paul" to "Paul")
    setNavHost(
        Route.WHOSE_CARE,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.whoseCarePatient("p-paul"))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.whoseCarePatient("p-paul")).performClick()

    // "Paul's care ▾" opens the list again, to choose Anne instead
    composeTestRule.onNodeWithTag(C.Tag.overview_whose_care).performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.whoseCarePatient("p-anne"))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.whoseCarePatient("p-anne")).performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertTextContains(context.getString(R.string.overview_care_of, "Anne"))
    composeTestRule.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()

    // Only the patient adds appointments
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
  }

  @Test
  fun caregiverMode_neverShowsTheCaregiversOwnAppointments() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne")
    // The caregiver's own account has an appointment: it must not look like Anne's
    val own = FakePlanningRepository()
    own.items.value =
        listOf(
            PlanningItem(
                PlanningSource.Appointment("own"),
                PlanningTiming.Timed(Instant.now().plus(1, ChronoUnit.HOURS)),
                "Own dentist",
            )
        )
    val events = FakePlanningRepository()
    setNavHost(
        Route.WHOSE_CARE,
        planningRepository = own,
        publicEvents = events,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.overview_whose_care)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()

    composeTestRule.onNodeWithText("Own dentist").assertDoesNotExist()
    composeTestRule.runOnIdle {
      // The Overview and Planning only read the Ligue's events
      assertTrue(own.ranges.isEmpty())
      assertTrue(events.ranges.isNotEmpty())
    }
  }

  @Test
  fun signingIn_leavesCaregiverMode() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne")
    val profiles = FakeUserProfileRepository()
    profiles.seed(UserProfile("patient", Role.PATIENT, "Alex"))
    UserProfileRepositoryProvider.repository = profiles
    setNavHost(
        Route.WHOSE_CARE,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.overview_whose_care)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    // Back on sign-in without signing out (e.g. the session expired), then a patient signs in
    composeTestRule.runOnIdle {
      NavigationActions(navController).navigateAndClearBackStack(Route.AUTH)
    }
    auth.onSignIn = { AuthUser(uid = "patient") }
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.overview_greeting).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.overview_whose_care).assertDoesNotExist()
  }

  @Test
  fun whoseCare_joinAnother_opensTheJoinScreen_andBackReturns() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne", "p-paul" to "Paul")
    setNavHost(
        Route.WHOSE_CARE,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.whose_care_join_another)
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.whose_care_join_another).performClick()
    composeTestRule.onNodeWithTag(C.Tag.join_circle_screen).assertIsDisplayed()

    composeTestRule.runOnIdle { NavigationActions(navController).goBack() }
    composeTestRule.onNodeWithTag(C.Tag.whose_care_screen).assertIsDisplayed()
  }

  @Test
  fun whoseCare_openedToSwitch_showsTheListAndABackArrow_evenWithOnePatient() {
    val (auth, invites, circles) = followingCaregiver("p-anne" to "Anne")
    setNavHost(
        Route.OVERVIEW,
        careCircleRepository = circles,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )
    composeTestRule.runOnIdle { navController.navigate(Screen.WHOSE_CARE_SWITCH) }
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodesWithTag(C.Tag.whoseCarePatient("p-anne"))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag(C.Tag.whose_care_back).performClick()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun whoseCare_signedOut_opensSignIn() {
    // Nobody signed in, as when the session expired
    setNavHost(Route.WHOSE_CARE)
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.sign_in_screen).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun joinACareCircle_signedOut_joiningOpensSignIn() {
    // Nobody signed in, as when the session expired
    setNavHost(Route.JOIN_CIRCLE)
    composeTestRule.onNodeWithTag(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    composeTestRule.onNodeWithTag(C.Tag.join_circle_join_button).performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.sign_in_screen).fetchSemanticsNodes().isNotEmpty()
    }
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun joinACareCircle_profileOpensTheProfile_andBackReturnsToJoin() {
    setNavHost(Route.JOIN_CIRCLE)
    composeTestRule.onNodeWithTag(C.Tag.join_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.join_circle_join_button).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.join_circle_profile).performClick()
    composeTestRule.runOnIdle {
      assertEquals(Screen.PROFILE, NavigationActions(navController).currentRoute())
      NavigationActions(navController).goBack()
    }
    composeTestRule.onNodeWithTag(C.Tag.join_circle_screen).assertIsDisplayed()
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

  @Test
  fun careCircle_listsPendingInvitesWithTheMembers() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val careCircle = FakeCareCircleRepository()
    careCircle.setMembers("owner", listOf(CareCircleMember(uid = "sophie", firstName = "Sophie")))
    val invites = FakeCareCircleInviteRepository()
    invites.setInvites(
        listOf(
            CareCircleInvite(
                inviteCode = "JJJJJ23456",
                ownerUid = "owner",
                ownerFirstName = "Anne",
                details = CareCircleMemberDetails(firstName = "Julie"),
            )
        )
    )
    setNavHost(
        Route.CARE_CIRCLE,
        careCircleRepository = careCircle,
        careCircleInviteRepository = invites,
        authRepository = auth,
    )

    // The shared invite repository reaches the list's ViewModel
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember("sophie")).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.careCircleInvite("JJJJJ23456")).assertIsDisplayed()
  }

  @Test
  fun careCircle_addingAMemberFromOverview_showsThemAsPendingInTheList() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val invites = FakeCareCircleInviteRepository().apply { nextInviteCode = { "K7M2PQ9XRT" } }
    UserProfileRepositoryProvider.repository =
        FakeUserProfileRepository().apply {
          seed(UserProfile(uid = "owner", role = Role.PATIENT, firstName = "Anne"))
        }
    setNavHost(Route.OVERVIEW, careCircleInviteRepository = invites, authRepository = auth)

    // Two taps from Overview (#74): the shortcut, then "Add a member" in the empty circle
    composeTestRule.onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty_add_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.CARE_CIRCLE_ADD, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_first_name).performTextInput("Julie")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_relationship).performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.careCircleFormRelationshipOption(Relationship.DAUGHTER.name))
        .performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.careCircleFormPermission(CarePermission.SYMPTOMS.name))
        .performScrollTo()
        .performClick()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_form_save_button)
        .performScrollTo()
        .performClick()

    // Back on the list, Julie waits to join with the access she was given
    composeTestRule.onNodeWithTag(C.Tag.care_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_pending_section).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.careCircleInvite("K7M2PQ9XRT"))
        .assertIsDisplayed()
        .assertTextContains("Julie", substring = true)
        .assertTextContains(context.getString(R.string.permission_symptoms), substring = true)
    composeTestRule.runOnIdle {
      assertEquals(Screen.CARE_CIRCLE, NavigationActions(navController).currentRoute())
    }
  }

  @Test
  fun careCircleForm_backArrow_returnsToTheList() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    setNavHost(Route.CARE_CIRCLE, authRepository = auth)

    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty_add_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_back_button).performClick()

    composeTestRule.onNodeWithTag(C.Tag.care_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_form_screen).assertDoesNotExist()
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
    composeTestRule.onNodeWithTag(C.Tag.planning_add).assertIsDisplayed()
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
    // The Overview reads the same planning data over a month; Planning's own week is read once
    composeTestRule.runOnIdle {
      assertEquals(
          1,
          repository.ranges.count {
            ChronoUnit.DAYS.between(it.startDateInclusive, it.endDateExclusive) == 7L
          },
      )
    }
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

  /**
   * Opens the journal from Overview as Alice, who has one symptom (`s1`), then deletes it from its
   * detail. Returns the repository, which no longer holds it.
   */
  private fun deleteTheOnlySymptomFromTheJournal(): FakeSymptomRepository {
    val symptoms =
        FakeSymptomRepository().apply {
          seed(
              "alice",
              SymptomEntry(
                  id = "s1",
                  types = listOf(SymptomType.FATIGUE),
                  occurredAt = Instant.now().minus(Duration.ofHours(1)),
              ),
          )
        }
    val alice =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    setNavHost(Route.OVERVIEW, authRepository = alice, symptomRepository = symptoms)
    openTheJournalFromOverview()

    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("s1")).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_delete).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_delete_confirm).performClick()
    // Back on the journal before doing anything else, as the user sees it
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
    composeTestRule.waitForIdle()
    return symptoms
  }

  private fun openTheJournalFromOverview() {
    composeTestRule
        .onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag)
        .performScrollTo()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
  }

  /** After going back from the journal, reopening it still doesn't show the deleted symptom. */
  private fun assertTheDeletedSymptomStaysGone() {
    assertOnTab(Tab.OVERVIEW, C.Tag.overview_screen)
    openTheJournalFromOverview()
    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("s1")).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_empty).assertIsDisplayed()
  }

  @Test
  fun deletedSymptom_returnsToTheJournalWithoutIt() {
    val symptoms = deleteTheOnlySymptomFromTheJournal()

    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("s1")).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOMS, NavigationActions(navController).currentRoute())
      assertTrue(symptoms.symptoms("alice").isEmpty())
    }
  }

  @Test
  fun deletedSymptom_staysGone_afterTheBackArrow() {
    deleteTheOnlySymptomFromTheJournal()

    // The detail of the deleted symptom is no longer behind the journal
    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()

    assertTheDeletedSymptomStaysGone()
  }

  @Test
  fun deletedSymptom_staysGone_afterSystemBack() {
    deleteTheOnlySymptomFromTheJournal()

    Espresso.pressBack()

    assertTheDeletedSymptomStaysGone()
  }

  @Test
  fun symptomJournalAddButton_opensTheAddScreen_andCloseReturns() {
    setNavHost(Route.SYMPTOMS)
    composeTestRule.onNodeWithTag(C.Tag.symptom_journal_add).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptom_add_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOM_ADD, NavigationActions(navController).currentRoute())
    }

    composeTestRule.onNodeWithTag(C.Tag.symptom_add_close).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
  }

  /**
   * Opens the edit screen of Alice's only symptom (`s1`, fatigue) from the journal and its detail,
   * as the user does. Returns the repository holding it.
   */
  private fun openTheEditScreenFromTheJournal(): FakeSymptomRepository {
    val symptoms =
        FakeSymptomRepository().apply {
          seed(
              "alice",
              SymptomEntry(
                  id = "s1",
                  types = listOf(SymptomType.FATIGUE),
                  occurredAt = Instant.now().minus(Duration.ofHours(1)),
              ),
          )
        }
    val alice =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    setNavHost(Route.OVERVIEW, authRepository = alice, symptomRepository = symptoms)
    openTheJournalFromOverview()
    composeTestRule.onNodeWithTag(C.Tag.symptomJournalItem("s1")).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_edit).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptom_add_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOM_EDIT, NavigationActions(navController).currentRoute())
      assertEquals(
          "s1",
          navController.currentBackStackEntry?.arguments?.getString(Screen.SYMPTOM_DETAIL_ID),
      )
    }
    return symptoms
  }

  @Test
  fun editedSymptom_returnsToTheDetail_andBothItAndTheJournalShowTheChange() {
    val symptoms = openTheEditScreenFromTheJournal()
    val fatigueAndPain =
        listOf(R.string.symptom_type_fatigue, R.string.symptom_type_pain).joinToString(", ") {
          context.getString(it)
        }

    composeTestRule
        .onNodeWithTag(C.Tag.symptomAddType(SymptomType.PAIN.name))
        .performScrollTo()
        .performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptom_add_save).performClick()

    // Back on the detail of the same symptom, which shows the change
    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(fatigueAndPain).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOM_DETAIL, NavigationActions(navController).currentRoute())
      assertEquals(
          listOf(SymptomType.FATIGUE, SymptomType.PAIN),
          symptoms.symptoms("alice").single().types,
      )
    }

    // And so does the journal behind it
    composeTestRule.onNodeWithTag(C.Tag.symptom_back_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.symptoms_screen).assertIsDisplayed()
    composeTestRule
        .onNode(
            hasText(fatigueAndPain, substring = true) and
                hasAnyAncestor(hasTestTag(C.Tag.symptomJournalItem("s1"))),
            useUnmergedTree = true,
        )
        .assertExists()
  }

  @Test
  fun closingTheEditScreen_returnsToTheDetail_withoutSaving() {
    val symptoms = openTheEditScreenFromTheJournal()

    composeTestRule.onNodeWithTag(C.Tag.symptom_add_close).performClick()

    composeTestRule.onNodeWithTag(C.Tag.symptom_detail_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SYMPTOM_DETAIL, NavigationActions(navController).currentRoute())
      assertEquals(listOf(SymptomType.FATIGUE), symptoms.symptoms("alice").single().types)
    }
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
    assertEquals("symptoms_add", Screen.SYMPTOM_ADD)
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
    composeTestRule.onNodeWithTag(C.Tag.planning_add).assertIsDisplayed()
  }
}
