// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteConfirmationDialogTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var confirms = 0
  private var cancels = 0

  private fun setContent(isDeleting: Boolean = false, hasError: Boolean = false) {
    composeTestRule.setContent {
      DeleteConfirmationDialog(
          title = "Delete this item?",
          message = "You can't undo this.",
          error = "Couldn't delete it.",
          confirmLabel = "Delete",
          cancelLabel = "Cancel",
          isDeleting = isDeleting,
          hasError = hasError,
          onConfirm = { confirms++ },
          onCancel = { cancels++ },
          tags = TAGS,
      )
    }
  }

  private fun assertTextInDialog(text: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(TAGS.dialog)), useUnmergedTree = true)
        .assertIsDisplayed()
  }

  @Test
  fun showsItsTitleMessageAndButtons_withoutAnError() {
    setContent()

    assertTextInDialog("Delete this item?")
    assertTextInDialog("You can't undo this.")
    assertTextInDialog("Delete")
    assertTextInDialog("Cancel")
    composeTestRule.onNodeWithTag(TAGS.error).assertDoesNotExist()
  }

  @Test
  fun confirmAndCancel_callBack() {
    setContent()

    composeTestRule.onNodeWithTag(TAGS.confirm).assertIsEnabled().performClick()
    composeTestRule.onNodeWithTag(TAGS.cancel).assertIsEnabled().performClick()

    assertEquals(1, confirms)
    assertEquals(1, cancels)
  }

  @Test
  fun failedDeletion_showsTheError_andCanBeRetried() {
    setContent(hasError = true)

    composeTestRule
        .onNode(hasTestTag(TAGS.error) and hasText("Couldn't delete it."))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(TAGS.confirm).assertIsEnabled()
  }

  @Test
  fun whileDeleting_bothButtonsAreDisabled() {
    setContent(isDeleting = true)

    composeTestRule.onNodeWithTag(TAGS.confirm).assertIsNotEnabled().performClick()
    composeTestRule.onNodeWithTag(TAGS.cancel).assertIsNotEnabled().performClick()

    assertEquals(0, confirms)
    assertEquals(0, cancels)
  }

  @Test
  fun back_cancels() {
    setContent()

    Espresso.pressBack()

    composeTestRule.runOnIdle { assertEquals(1, cancels) }
  }

  @Test
  fun backWhileDeleting_doesNotCancel() {
    setContent(isDeleting = true)

    Espresso.pressBack()

    composeTestRule.runOnIdle { assertEquals(0, cancels) }
  }

  private companion object {
    val TAGS =
        DeleteDialogTags(
            dialog = "dialog",
            confirm = "confirm",
            cancel = "cancel",
            error = "error",
        )
  }
}
