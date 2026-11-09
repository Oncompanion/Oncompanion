// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LakeDialog] for a decision that isn't destructive (e.g. signing out). The Figma "Dialog" itself,
 * destructive, is checked by `DeleteConfirmationDialogTest`.
 */
@RunWith(AndroidJUnit4::class)
class LakeDialogTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified
  private var dark by mutableStateOf(false)

  private fun setContent(isWorking: Boolean = false) {
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) {
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        LakeDialog(
            title = "Sign out?",
            message = "You can sign in again at any time.",
            confirmLabel = "Sign out",
            cancelLabel = "Cancel",
            onConfirm = {},
            onCancel = {},
            tags = TAGS,
            isWorking = isWorking,
        )
      }
    }
  }

  // Only a destructive action is red
  @Test
  fun aDecisionThatIsntDestructive_hasBothLabelsInPrimaryText_inBothThemes() {
    setContent()

    for (theme in listOf(false, true)) {
      dark = theme
      composeTestRule.waitForIdle()
      for (label in listOf("Sign out", "Cancel")) {
        composeTestRule
            .onNodeWithText(label, useUnmergedTree = true)
            .assertTextStyle(typography.buttonLabel, primaryText)
      }
    }
  }

  @Test
  fun working_showsTheSpinner_andDisablesBothButtons() {
    setContent(isWorking = true)

    composeTestRule.onNodeWithTag(C.Tag.dialog_progress).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TAGS.confirm).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(TAGS.cancel).assertIsNotEnabled()
  }

  private companion object {
    val TAGS =
        DialogTags(dialog = "dialog", confirm = "confirm", cancel = "cancel", error = "error")
  }
}
