// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class DeleteConfirmationDialogTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var confirms = 0
  private var cancels = 0

  private lateinit var colors: ColorScheme
  private var primaryText = Color.Unspecified
  private lateinit var typography: Typography

  private fun setContent(
      isDeleting: Boolean = false,
      hasError: Boolean = false,
      message: String = "You can't undo this.",
      confirmLabel: String = "Delete",
      cancelLabel: String = "Cancel",
  ) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        primaryText = MaterialTheme.lakeColors.primaryText
        typography = MaterialTheme.typography
        DeleteConfirmationDialog(
            title = "Delete this item?",
            message = message,
            error = "Couldn't delete it.",
            confirmLabel = confirmLabel,
            cancelLabel = cancelLabel,
            isDeleting = isDeleting,
            hasError = hasError,
            onConfirm = { confirms++ },
            onCancel = { cancels++ },
            tags = TAGS,
        )
      }
    }
  }

  /** Fails unless [text] was laid out in [expected], in [color]. */
  private fun assertStyle(text: String, expected: TextStyle, color: Color) =
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertTextStyle(expected, color)

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
    composeTestRule.onNodeWithTag(C.Tag.dialog_progress).assertDoesNotExist()
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

  // ----- Figma: "Dialog" (Brand & Theme) -----

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun dialog_is312dpWide_onWhite() {
    setContent()

    val dialog = composeTestRule.onNodeWithTag(TAGS.dialog)
    dialog.assertWidthIsEqualTo(312.dp)
    // Inside the 28dp top left corner, above the title
    assertSameColor("container", colors.surfaceContainerLowest, dialog.colorAt(20.dp, 12.dp))
  }

  @Test
  fun texts_useTheFigmaStyles() {
    setContent(hasError = true)

    assertStyle("Delete this item?", typography.titleLarge, colors.onSurface)
    assertStyle("You can't undo this.", typography.bodyMedium, colors.onSurfaceVariant)
    assertStyle("Couldn't delete it.", typography.bodyMedium, colors.error)
    assertStyle("Cancel", typography.buttonLabel, primaryText)
    // Only the destructive action is red
    assertStyle("Delete", typography.buttonLabel, colors.error)
  }

  @Test
  fun buttons_are56dpTall_cancelThenDelete_onOneLine() {
    setContent()

    composeTestRule.onNodeWithTag(TAGS.confirm).assertHeightIsEqualTo(56.dp)
    composeTestRule.onNodeWithTag(TAGS.cancel).assertHeightIsEqualTo(56.dp)
    val cancel = composeTestRule.onNodeWithTag(TAGS.cancel).getUnclippedBoundsInRoot()
    val confirm = composeTestRule.onNodeWithTag(TAGS.confirm).getUnclippedBoundsInRoot()
    assertEquals(cancel.top, confirm.top)
    assertEquals(cancel.right, confirm.left)
  }

  @Test
  fun whileDeleting_aSpinnerComesBeforeTheButtons_andDeleteIsNoLongerRed() {
    setContent(isDeleting = true)

    val spinner = composeTestRule.onNodeWithTag(C.Tag.dialog_progress).getUnclippedBoundsInRoot()
    val cancel = composeTestRule.onNodeWithTag(TAGS.cancel).getUnclippedBoundsInRoot()
    assertEquals(cancel.left, spinner.right)
    assertStyle("Delete", typography.buttonLabel, primaryText)
  }

  // Real text drawing: Robolectric otherwise measures text far too narrow to ever wrap
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun labelsTooLongForOneLine_stackDeleteAboveCancel_onTheRight() {
    setContent(confirmLabel = "Delete it for good", cancelLabel = "Keep this item")

    val confirm = composeTestRule.onNodeWithTag(TAGS.confirm).getUnclippedBoundsInRoot()
    val cancel = composeTestRule.onNodeWithTag(TAGS.cancel).getUnclippedBoundsInRoot()
    assertEquals(confirm.bottom, cancel.top)
    assertEquals(confirm.right, cancel.right)
  }

  // A Delete label as wide as the dialog left no room for the spinner beside it
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun stackedWhileDeleting_theSpinnerStaysInTheDialog_nextToCancel() {
    setContent(isDeleting = true, confirmLabel = "Delete this item and all of its notes")

    val dialog = composeTestRule.onNodeWithTag(TAGS.dialog).getUnclippedBoundsInRoot()
    val spinner = composeTestRule.onNodeWithTag(C.Tag.dialog_progress).getUnclippedBoundsInRoot()
    val cancel = composeTestRule.onNodeWithTag(TAGS.cancel).getUnclippedBoundsInRoot()
    assertTrue(spinner.left >= dialog.left)
    assertEquals(cancel.left, spinner.right)
    assertTrue(spinner.top >= cancel.top && spinner.bottom <= cancel.bottom)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE, fontScale = 2f)
  fun smallLandscape_at200PercentFont_theButtonsStayFullyVisible() {
    setContent(hasError = true, message = "You can't undo this. ".repeat(6))

    composeTestRule.onNodeWithTag(TAGS.confirm).assertIsFullyVisible()
    composeTestRule.onNodeWithTag(TAGS.cancel).assertIsFullyVisible()
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
