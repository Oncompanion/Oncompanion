package com.github.se.oncompanion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

  @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun launchingMainActivity_displaysMainContainer() {
    composeTestRule.onNodeWithTag(C.Tag.main_screen_container).assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_startsOnSignInScreen() {
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(composeTestRule.activity.getString(R.string.sign_in_title))
        .assertIsDisplayed()
  }

  @Test
  fun launchingMainActivity_doesNotShowOtherScreens() {
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
  }
}
