package com.github.se.oncompanion.ui.overview

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Before
  fun setUp() {
    composeTestRule.setContent {
      val navigationActions = NavigationActions(TestNavHostController(LocalContext.current))
      OverviewScreen(navigationActions = navigationActions)
    }
  }

  @Test
  fun overviewScreen_hasOverviewTestTag() {
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  @Test
  fun overviewScreen_displaysOverviewTitle() {
    composeTestRule.onNodeWithText(context.getString(R.string.overview_welcome)).assertIsDisplayed()
  }
}
