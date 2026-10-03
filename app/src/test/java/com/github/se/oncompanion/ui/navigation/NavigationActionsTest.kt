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
  fun navigateToTopLevel_displaysStartScreenOfGraph() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.ONBOARDING) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.ONBOARDING_ROLE, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateToTopLevel_removesPreviousFlowFromBackStack() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.ONBOARDING) }
    composeTestRule.runOnIdle {
      val routes = routesInBackStack()
      assertFalse("back stack still contains sign-in: $routes", routes.contains(Screen.SIGN_IN))
      assertFalse("back stack still contains auth graph: $routes", routes.contains(Route.AUTH))
      assertTrue(routes.contains(Route.ONBOARDING))
      assertTrue(routes.contains(Screen.ONBOARDING_ROLE))
    }
  }

  @Test
  fun navigateToTopLevel_leavesNoScreenBelowNewStartScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.ONBOARDING) }
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
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.ONBOARDING) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { navController.popBackStack() }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
  }

  @Test
  fun goBack_afterNavigateToTopLevel_doesNotShowPreviousFlow() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.OVERVIEW) }
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    composeTestRule.runOnIdle { navigationActions.goBack() }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
  }

  @Test
  fun navigateToTopLevel_fromOnboardingToOverview_showsOverviewWithCleanBackStack() {
    setNavHost(Route.ONBOARDING)
    composeTestRule.runOnIdle { navigationActions.navigateTo(Screen.ONBOARDING_INFORMATION) }
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.OVERVIEW) }
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
  fun navigateToTopLevel_sameRouteTwice_doesNotDuplicateStartScreen() {
    setNavHost(Route.AUTH)
    composeTestRule.runOnIdle {
      navigationActions.navigateToTopLevel(Route.OVERVIEW)
      navigationActions.navigateToTopLevel(Route.OVERVIEW)
    }
    composeTestRule.runOnIdle {
      assertEquals(1, routesInBackStack().count { it == Screen.OVERVIEW })
      assertEquals(Screen.OVERVIEW, navigationActions.currentRoute())
    }
  }

  @Test
  fun navigateToTopLevel_fromOverviewToAuth_showsSignInAndDropsOverview() {
    setNavHost(Route.OVERVIEW)
    composeTestRule.runOnIdle { navigationActions.navigateToTopLevel(Route.AUTH) }
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.SIGN_IN, navigationActions.currentRoute())
      assertFalse(routesInBackStack().contains(Screen.OVERVIEW))
    }
  }
}
