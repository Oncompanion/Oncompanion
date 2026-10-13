// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveButtonTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var clicks = 0

  private fun setContent(enabled: Boolean = true, isSaving: Boolean = false) {
    composeTestRule.setContent {
      SaveButton(
          text = "Save",
          enabled = enabled,
          isSaving = isSaving,
          onClick = { clicks++ },
          testTag = BUTTON,
          savingTestTag = SAVING,
      )
    }
  }

  @Test
  fun enabled_showsItsText_andReportsClicks() {
    setContent()

    composeTestRule.onNodeWithTag(BUTTON).assert(hasText("Save")).assertIsEnabled().performClick()

    assertEquals(1, clicks)
    composeTestRule.onNodeWithTag(SAVING).assertDoesNotExist()
  }

  @Test
  fun disabled_cantBeClicked() {
    setContent(enabled = false)

    composeTestRule.onNodeWithTag(BUTTON).assertIsNotEnabled().performClick()

    assertEquals(0, clicks)
  }

  @Test
  fun saving_showsTheSpinnerInsteadOfTheText() {
    setContent(enabled = false, isSaving = true)

    composeTestRule.onNodeWithTag(SAVING, useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag(BUTTON).assert(hasText("Save").not())
  }

  private companion object {
    const val BUTTON = "button"
    const val SAVING = "saving"
  }
}
