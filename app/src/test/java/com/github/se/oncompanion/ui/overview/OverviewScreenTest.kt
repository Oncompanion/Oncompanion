package com.github.se.oncompanion.ui.overview

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val mainShortcuts =
      listOf(
          OverviewShortcut.SYMPTOMS,
          OverviewShortcut.PRESCRIPTIONS,
          OverviewShortcut.CARE_CIRCLE,
      )

  /** Records the routes it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedRoutes = mutableListOf<String>()

    override fun navigateTo(screen: String) {
      openedRoutes += screen
    }
  }

  private fun setOverviewScreen(): RecordingNavigationActions {
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      OverviewScreen(navigationActions = navigationActions)
    }
    return navigationActions
  }

  @Test
  fun overviewScreen_hasOverviewTestTag() {
    setOverviewScreen()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun overviewScreen_withoutFirstName_showsPlainWelcomeAndSubtitle() {
    setOverviewScreen()
    composeTestRule.onNodeWithText(context.getString(R.string.overview_welcome)).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.overview_subtitle))
        .assertIsDisplayed()
  }

  @Test
  fun overviewContent_withFirstName_welcomesTheUserByName() {
    composeTestRule.setContent { OverviewContent(firstName = "Alex", onShortcutClick = {}) }
    composeTestRule
        .onNodeWithText(context.getString(R.string.overview_welcome_name, "Alex"))
        .assertIsDisplayed()
  }

  @Test
  fun overviewContent_withBlankFirstName_showsPlainWelcome() {
    composeTestRule.setContent { OverviewContent(firstName = " ", onShortcutClick = {}) }
    composeTestRule.onNodeWithText(context.getString(R.string.overview_welcome)).assertIsDisplayed()
  }

  @Test
  fun overviewScreen_showsMainShortcutsWithTheirLabel() {
    setOverviewScreen()
    mainShortcuts.forEach { shortcut ->
      composeTestRule
          .onNode(hasTestTag(shortcut.testTag) and hasText(context.getString(shortcut.label)))
          .performScrollTo()
          .assertIsDisplayed()
          .assertHasClickAction()
    }
  }

  @Test
  fun overviewScreen_showsProfileButtonWithContentDescription() {
    setOverviewScreen()
    composeTestRule
        .onNode(
            hasTestTag(OverviewShortcut.PROFILE.testTag) and
                hasContentDescription(context.getString(R.string.profile_title))
        )
        .assertIsDisplayed()
        .assertHasClickAction()
  }

  @Test
  fun clickingEachShortcut_opensItsSectionRoute() {
    val navigationActions = setOverviewScreen()

    mainShortcuts.forEach { shortcut ->
      composeTestRule.onNodeWithTag(shortcut.testTag).performScrollTo().performClick()
    }
    composeTestRule.onNodeWithTag(OverviewShortcut.PROFILE.testTag).performClick()

    composeTestRule.runOnIdle {
      assertEquals(
          listOf(Route.SYMPTOMS, Route.PRESCRIPTIONS, Route.CARE_CIRCLE, Route.PROFILE),
          navigationActions.openedRoutes,
      )
    }
  }

  @Test
  fun overviewContent_reportsTheClickedShortcut() {
    val clicked = mutableListOf<OverviewShortcut>()
    composeTestRule.setContent {
      OverviewContent(firstName = null, onShortcutClick = { clicked += it })
    }

    composeTestRule
        .onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag)
        .performScrollTo()
        .performClick()

    composeTestRule.runOnIdle { assertEquals(listOf(OverviewShortcut.CARE_CIRCLE), clicked) }
  }
}
