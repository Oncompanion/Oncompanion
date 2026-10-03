package com.github.se.oncompanion.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaceholderScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Before
  fun setUp() {
    composeTestRule.setContent { PlaceholderScreen(title = "Hello title", testTag = "my_tag") }
  }

  @Test
  fun placeholderScreen_rootHasGivenTestTag() {
    composeTestRule.onNodeWithTag("my_tag").assertIsDisplayed()
  }

  @Test
  fun placeholderScreen_displaysTitle() {
    composeTestRule.onNodeWithText("Hello title").assertIsDisplayed()
  }

  @Test
  fun placeholderScreen_titleIsInsideTaggedRoot() {
    composeTestRule
        .onNode(hasText("Hello title") and hasAnyAncestor(hasTestTag("my_tag")), true)
        .assertIsDisplayed()
  }
}
