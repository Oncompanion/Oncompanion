package com.github.se.oncompanion.ui.navigation

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.overview.OverviewShortcut
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavHostTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var navController: TestNavHostController

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun setNavHost(startRoute: String? = null) {
    composeTestRule.setContent {
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
          Destination(Route.EVENTS, Screen.EVENTS, C.Tag.events_screen, R.string.events_title),
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
              R.string.prescriptions_title,
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
                  hasAnyAncestor(hasTestTag(destination.testTag))
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
  fun routeAndScreenConstants_haveExpectedValues() {
    assertEquals("auth", Route.AUTH)
    assertEquals("onboarding", Route.ONBOARDING)
    assertEquals("overview", Route.OVERVIEW)
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
}
