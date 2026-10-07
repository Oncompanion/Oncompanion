package com.github.se.oncompanion.ui.navigation

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.NavGraph
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationActionsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var navController: TestNavHostController
  private lateinit var navigationActions: NavigationActions

  private fun setNavHost(startRoute: String) {
    composeTestRule.setContent {
      navController =
          TestNavHostController(LocalContext.current).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
          }
      AppNavHost(navController = navController, startRoute = startRoute)
    }
    composeTestRule.runOnIdle { navigationActions = NavigationActions(navController) }
  }

  private fun routesInBackStack(): List<String?> =
      navController.backStack.map { it.destination.route }

  @Test
  fun currentRoute_returnsStartScreenOfStartRoute() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { assertEquals(Screen.SIGN_IN, navigationActions.currentRoute()) }
  }

  @Test
  fun currentRoute_returnsEmptyStringWhenNoDestination() {
    val emptyController = TestNavHostController(ApplicationProvider.getApplicationContext())
    assertEquals("", NavigationActions(emptyController).currentRoute())
  }

  @Test
  fun navigateTo_displaysScreenAndUpdatesCurrentRoute() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_INFORMATION, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateTo_keepsPreviousScreenInBackStack() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.runOnIdle {
      assertEquals(
          Screen.ONBOARDING_ROLE,
          navController.previousBackStackEntry?.destination?.route,
      )
    }
  }

  @Test
  fun goBack_afterNavigateTo_returnsToPreviousScreen() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_ROLE, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateTo_sameScreenTwice_doesNotDuplicateIt() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle {
      navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION)
      navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION)
    }
    composeTestRule.runOnIdle {
      assertEquals(1, routesInBackStack().count { it == Screen.ONBOARDING_INFORMATION })
      assertEquals(
          Screen.ONBOARDING_ROLE,
          navController.previousBackStackEntry?.destination?.route,
      )
    }
  }

  @Test
  fun navigateTo_currentScreen_doesNotDuplicateIt() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_ROLE) }
    composeTestRule.runOnIdle {
      assertEquals(1, routesInBackStack().count { it == Screen.ONBOARDING_ROLE })
      assertEquals(Screen.ONBOARDING_ROLE, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateAndClearBackStack_displaysStartScreenOfGraph() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.ONBOARDING) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_ROLE, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateAndClearBackStack_removesPreviousFlowFromBackStack() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.ONBOARDING) }
    composeTestRule.runOnIdle {
      val routes = routesInBackStack()
      assertFalse("back stack still contains sign-in: $routes", routes.contains(Screen.SIGN_IN))
      assertFalse("back stack still contains auth graph: $routes", routes.contains(Route.AUTH))
      assertTrue(routes.contains(Route.ONBOARDING))
      assertTrue(routes.contains(Screen.ONBOARDING_ROLE))
    }
  }

  @Test
  fun navigateAndClearBackStack_leavesNoScreenBelowNewStartScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.ONBOARDING) }
    composeTestRule.runOnIdle {
      // Only navigation graphs (no screen) may remain below the new start screen.
      val screensBelow =
          navController.backStack.dropLast(1).map { it.destination }.filter { it !is NavGraph }
      assertTrue("screens left below the new flow: $screensBelow", screensBelow.isEmpty())
    }
  }

  @Test
  fun popBackStack_afterNavigateToTopLevel_doesNotShowPreviousScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.ONBOARDING) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { navController.popBackStack() }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
  }

  @Test
  fun goBack_afterNavigateToTopLevel_doesNotShowPreviousFlow() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.OVERVIEW) }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    // The new flow's screen stays: the NavHost is never emptied
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { assertEquals(Screen.OVERVIEW, navigationActions.currentRoute()) }
  }

  @Test
  fun goBack_onStartScreen_keepsItDisplayed() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, navigationActions.currentRoute())
      assertEquals(1, routesInBackStack().count { it == Screen.SIGN_IN })
    }
  }

  @Test
  fun navigateAndClearBackStack_fromOnboardingToOverview_showsOverviewWithCleanBackStack() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.OVERVIEW) }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.OVERVIEW, navigationActions.currentRoute())
      val routes = routesInBackStack()
      assertFalse(routes.contains(Screen.ONBOARDING_ROLE))
      assertFalse(routes.contains(Screen.ONBOARDING_INFORMATION))
      assertFalse(routes.contains(Route.ONBOARDING))
    }
  }

  @Test
  fun navigateAndClearBackStack_sameRouteTwice_doesNotDuplicateStartScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle {
      navigationActions.navigateAndClearBackStack(Route.OVERVIEW)
      navigationActions.navigateAndClearBackStack(Route.OVERVIEW)
    }
    composeTestRule.runOnIdle {
      assertEquals(1, routesInBackStack().count { it == Screen.OVERVIEW })
      assertEquals(Screen.OVERVIEW, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateAndClearBackStack_fromOverviewToAuth_showsSignInAndDropsOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle { navigationActions.navigateAndClearBackStack(Route.AUTH) }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, navigationActions.currentRoute())
      assertFalse(routesInBackStack().contains(Screen.OVERVIEW))
    }
  }

  private fun screensInBackStack(): List<String?> =
      navController.backStack.filter { it.destination !is NavGraph }.map { it.destination.route }

  @Test
  fun navigateToTab_opensTheTab_andBackReturnsToOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle { navigationActions.navigateToTab(Route.PLANNING) }
    composeTestRule.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()

    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun navigateToTab_betweenTabs_keepsOnlyOverviewBelow() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle {
      navigationActions.navigateToTab(Route.PLANNING)
      navigationActions.navigateToTab(Route.EVENTS)
    }
    composeTestRule.onNodeWithTag(C.Tag.events_screen).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(listOf(Screen.OVERVIEW, Screen.EVENTS), screensInBackStack())
    }

    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun navigateToTab_currentTab_doesNotPushACopy() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle {
      navigationActions.navigateToTab(Route.PLANNING)
      navigationActions.navigateToTab(Route.PLANNING)
    }
    composeTestRule.runOnIdle {
      assertEquals(listOf(Screen.OVERVIEW, Screen.PLANNING), screensInBackStack())
    }
  }

  @Test
  fun navigateToTab_overviewFromAnotherTab_leavesOnlyOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle {
      navigationActions.navigateToTab(Route.EVENTS)
      navigationActions.navigateToTab(Route.OVERVIEW)
    }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { assertEquals(listOf(Screen.OVERVIEW), screensInBackStack()) }
  }

  @Test
  fun symptomDetail_putsTheIdInTheRoute() {
    assertEquals("symptoms_detail/{symptomId}", Screen.SYMPTOM_DETAIL)
    assertEquals("symptomId", Screen.SYMPTOM_DETAIL_ID)
    assertEquals("symptoms_detail/abc", Screen.symptomDetail("abc"))
  }
}
