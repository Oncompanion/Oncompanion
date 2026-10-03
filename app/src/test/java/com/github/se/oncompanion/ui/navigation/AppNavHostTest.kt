package com.github.se.oncompanion.ui.navigation

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
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
    composeTestRule.onNodeWithText(context.getString(R.string.sign_in_title)).assertIsDisplayed()
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
    composeTestRule.onNodeWithText(context.getString(R.string.overview_title)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(Screen.OVERVIEW, NavigationActions(navController).currentRoute())
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
  fun routeAndScreenConstants_haveExpectedValues() {
    assertEquals("auth", Route.AUTH)
    assertEquals("onboarding", Route.ONBOARDING)
    assertEquals("overview", Route.OVERVIEW)
    assertEquals("sign_in", Screen.SIGN_IN)
    assertEquals("onboarding_role", Screen.ONBOARDING_ROLE)
    assertEquals("onboarding_information", Screen.ONBOARDING_INFORMATION)
    assertEquals("overview_home", Screen.OVERVIEW)
  }
}
