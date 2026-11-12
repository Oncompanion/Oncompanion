// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

  private fun setContent(isWorking: Boolean = false, focusCancel: Boolean = false) {
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
            focusCancel = focusCancel,
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

  // E.g. "Stay signed in" when signing out would discard changes not yet saved online
  @Test
  fun focusCancel_putsTheFocusOnCancelFirst() {
    setContent(focusCancel = true)

    composeTestRule.onNodeWithTag(TAGS.cancel).assertIsFocused()
  }

  // E.g. a tip to acknowledge, with a checkbox under its message
  @Test
  fun withoutCancel_showsTheContentAndTheConfirmButtonAlone() {
    var confirmed = 0
    composeTestRule.setContent {
      OncompanionTheme {
        LakeDialog(
            title = "Hold your phone upright",
            message = "A photo taken sideways is read less well.",
            confirmLabel = "Got it",
            cancelLabel = null,
            onConfirm = { confirmed++ },
            onCancel = {},
            tags = TAGS,
            // Nothing to focus without Cancel: must not fail
            focusCancel = true,
        ) {
          Text("Don't show this again", Modifier.testTag("content"))
        }
      }
    }

    composeTestRule.onNodeWithTag(TAGS.cancel).assertDoesNotExist()
    val message =
        composeTestRule.onNodeWithText("A photo taken sideways is read less well.").bounds()
    val content = composeTestRule.onNodeWithTag("content").bounds()
    val confirm = composeTestRule.onNodeWithTag(TAGS.confirm).bounds()
    // Under the message, above the button, and the button at the dialog's end
    assertTrue(message.bottom <= content.top && content.bottom <= confirm.top)
    val dialog = composeTestRule.onNodeWithTag(TAGS.dialog).bounds()
    assertTrue(dialog.right - confirm.right < dialog.width / 4)

    composeTestRule.onNodeWithTag(TAGS.confirm).performClick()
    assertEquals(1, confirmed)
  }

  private fun SemanticsNodeInteraction.bounds() = fetchSemanticsNode().boundsInRoot

  private companion object {
    val TAGS =
        DialogTags(dialog = "dialog", confirm = "confirm", cancel = "cancel", error = "error")
  }
}
