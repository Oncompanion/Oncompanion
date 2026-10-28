// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DetailActionsTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var edits = 0
  private var deletes = 0

  /** A detail of [rows] rows of 80dp: 30 of them never fit on a phone. */
  private fun setContent(rows: Int = 1, showActions: Boolean = true) {
    composeTestRule.setContent {
      DetailWithActions(
          showActions = showActions,
          contentTestTag = CONTENT,
          actions = {
            DetailActions(
                editLabel = "Edit item",
                deleteLabel = "Delete item",
                onEdit = { edits++ },
                onDelete = { deletes++ },
                tags = TAGS,
            )
          },
      ) {
        Column { repeat(rows) { Text("Row $it", Modifier.height(80.dp).testTag(row(it))) } }
      }
    }
  }

  private fun row(index: Int) = "row_$index"

  private fun yOf(tag: String) =
      composeTestRule
          .onNodeWithTag(tag, useUnmergedTree = true)
          .fetchSemanticsNode()
          .positionInRoot
          .y

  @Test
  fun actions_showTheirLabels_editAboveDelete_andCallBack() {
    setContent()

    // A button merges its label into itself
    composeTestRule.onNode(hasTestTag(TAGS.edit) and hasText("Edit item")).assertIsDisplayed()
    composeTestRule.onNode(hasTestTag(TAGS.delete) and hasText("Delete item")).assertIsDisplayed()
    assertTrue(yOf(TAGS.edit) < yOf(TAGS.delete))

    composeTestRule.onNodeWithTag(TAGS.edit).performClick()
    composeTestRule.onNodeWithTag(TAGS.delete).performClick()

    assertEquals(1, edits)
    assertEquals(1, deletes)
  }

  @Test
  fun hiddenActions_showOnlyTheContent() {
    setContent(showActions = false)

    composeTestRule.onNodeWithTag(row(0)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TAGS.edit).assertDoesNotExist()
    composeTestRule.onNodeWithTag(TAGS.delete).assertDoesNotExist()
  }

  @Test
  fun portrait_actionsStayOnScreen_whileALongDetailScrolls() {
    setContent(rows = 30)

    composeTestRule.onNodeWithTag(TAGS.delete).assertIsFullyVisible()
    composeTestRule.onNodeWithTag(row(29)).assertIsNotDisplayed()

    composeTestRule.onNodeWithTag(CONTENT).performScrollToNode(hasTestTag(row(29)))

    composeTestRule.onNodeWithTag(row(29)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TAGS.delete).assertIsFullyVisible()
    assertTrue(yOf(row(29)) < yOf(TAGS.edit))
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun shortWindow_actionsScrollAfterALongDetail_soItKeepsTheWholeHeight() {
    setContent(rows = 30)

    // Not pinned: the detail starts at the top and fills the window
    composeTestRule.onNodeWithTag(TAGS.edit).assertIsNotDisplayed()

    composeTestRule.onNodeWithTag(CONTENT).performScrollToNode(hasTestTag(TAGS.delete))

    composeTestRule.onNodeWithTag(TAGS.delete).assertIsDisplayed()
    assertTrue(yOf(row(29)) < yOf(TAGS.edit))
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun shortWindow_shortDetail_showsTheActionsWithoutScrolling() {
    setContent(rows = 1)

    composeTestRule.onNodeWithTag(TAGS.edit).assertIsDisplayed()
    composeTestRule.onNodeWithTag(TAGS.delete).assertIsFullyVisible()
  }

  private companion object {
    const val CONTENT = "content"
    val TAGS = DetailActionTags(edit = "edit", delete = "delete")
  }
}
