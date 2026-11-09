// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LakeTextField] as the Figma "Text field" (Brand & Theme), state by state. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeTextFieldTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var value by mutableStateOf("")

  /** The field on the theme's surface, with a "Label" and a "Helper" below it. */
  private fun setContent(text: String = "", isError: Boolean = false, isCheck: Boolean = false) {
    value = text
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        Surface(color = MaterialTheme.colorScheme.surface) {
          LakeTextField(
              value = value,
              onValueChange = { value = it },
              label = { Text("Label") },
              supportingText = { Text("Helper") },
              isError = isError,
              isCheck = isCheck,
              singleLine = true,
              modifier = Modifier.testTag(FIELD),
          )
        }
      }
    }
  }

  private fun field() = composeTestRule.onNodeWithTag(FIELD)

  private fun text(text: String) = composeTestRule.onNodeWithText(text, useUnmergedTree = true)

  /** The color in the middle of the box's top edge, [depth] below it. */
  private fun topEdgeAt(depth: Dp): Color {
    val width = field().getUnclippedBoundsInRoot().let { it.right - it.left }
    return field().colorAt(width / 2, depth)
  }

  @Test
  fun empty_showsTheLabelInsideTheBox_withA1dpOutline_andMediumCorners() {
    setContent()

    text("Label").assertTextStyle(typography.bodyLarge, colors.onSurfaceVariant)
    text("Helper").assertTextStyle(typography.bodySmall, colors.onSurfaceVariant)
    assertSameColor("border", colors.outline, topEdgeAt(0.5.dp))
    assertSameColor("inside", colors.surface, topEdgeAt(1.5.dp))
    // The top left corner is rounded by 12 dp: its very corner shows the surface
    assertSameColor("corner", colors.surface, field().colorAt(1.dp, 1.dp))
  }

  @Test
  fun filled_showsTheLabelSmallAboveTheValue_insideTheBox() {
    setContent(text = "28 Oct 2026")

    text("Label").assertTextStyle(typography.bodySmall, colors.onSurfaceVariant)
    text("28 Oct 2026").assertTextStyle(typography.bodyLarge, colors.onSurface)
    // Material shrinks the label by scaling it, so compare centers rather than edges
    val label = text("Label").getUnclippedBoundsInRoot()
    val value = text("28 Oct 2026").getUnclippedBoundsInRoot()
    val box = field().getUnclippedBoundsInRoot()
    assertTrue("the label is inside the box", label.top > box.top)
    val labelCenter = (label.top + label.bottom) / 2
    assertTrue("the label is above the value", labelCenter < (value.top + value.bottom) / 2)
  }

  @Test
  fun error_hasA1_5dpRedBorder_aRedLabel_andARedHelper() {
    setContent(text = "02:30", isError = true)

    assertSameColor("border", colors.error, topEdgeAt(1.dp))
    assertSameColor("inside", colors.surface, topEdgeAt(2.dp))
    text("Label").assertTextStyle(typography.bodySmall, colors.error)
    text("Helper").assertTextStyle(typography.bodySmall, colors.error)
  }

  @Test
  fun check_isOnDawn_withA1_5dpSecondaryBorder() {
    setContent(text = "29 Sep 2026", isCheck = true)

    assertSameColor("border", colors.secondary, topEdgeAt(1.dp))
    assertSameColor("inside", colors.secondaryContainer, topEdgeAt(2.dp))
    text("Label").assertTextStyle(typography.bodySmall, colors.onSecondaryContainer)
    text("29 Sep 2026").assertTextStyle(typography.bodyLarge, colors.onSecondaryContainer)
    text("Helper").assertTextStyle(typography.bodySmall, colors.secondary)
  }

  @Test
  fun typing_focusesTheField_withA2dpPrimaryBorder_andReportsTheText() {
    setContent()

    field().performClick().assertIsFocused().performTextInput("Fatigue")
    composeTestRule.waitForIdle()

    assertEquals("Fatigue", value)
    assertSameColor("border", colors.primary, topEdgeAt(1.5.dp))
    assertSameColor("inside", colors.surface, topEdgeAt(2.5.dp))
  }

  @Test
  fun focusing_neverMakesTheFieldTaller_soItStaysInAScrolledView() {
    setContent()
    val empty = field().getUnclippedBoundsInRoot().let { it.bottom - it.top }

    field().performClick()
    composeTestRule.waitForIdle()

    val focused = field().getUnclippedBoundsInRoot().let { it.bottom - it.top }
    assertTrue("empty $empty, focused $focused", focused <= empty)
  }

  @Test
  fun theHelper_sitsBelowTheBox_outsideItsBorder() {
    setContent(text = "Value")

    val helper = text("Helper").getUnclippedBoundsInRoot()
    val field = field().getUnclippedBoundsInRoot()
    // Left of the helper, where the box's left border would be if it wrapped the helper too
    val besideHelper = field().colorAt(0.5.dp, (helper.top + helper.bottom) / 2 - field.top)
    assertSameColor("beside the helper", colors.surface, besideHelper)
  }

  /** One field per [FORM] case: empty or filled, with or without a helper. */
  private fun setForm() {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        Surface(color = MaterialTheme.colorScheme.surface) {
          Column {
            for ((tag, text, helper) in FORM) {
              LakeTextField(
                  value = text,
                  onValueChange = {},
                  label = { Text("Label") },
                  supportingText = if (helper) ({ Text("Helper") }) else null,
                  singleLine = true,
                  modifier = Modifier.testTag(tag),
              )
            }
          }
        }
      }
    }
  }

  /**
   * Fails unless every field of [setForm] has its box's bottom border [height] below its top, so
   * fields side by side line up whether they have a helper (e.g. "*Required") or a value.
   */
  private fun assertEveryBoxIs(height: Dp) {
    for ((tag) in FORM) {
      val node = composeTestRule.onNodeWithTag(tag)
      val middle = node.getUnclippedBoundsInRoot().let { it.right - it.left } / 2
      assertSameColor("$tag, inside", colors.surface, node.colorAt(middle, height - 1.5.dp))
      assertSameColor("$tag, bottom border", colors.outline, node.colorAt(middle, height - 0.5.dp))
    }
  }

  @Test
  fun theBox_is58dpTall_emptyOrFilled_withOrWithoutAHelper() {
    setForm()

    assertEveryBoxIs(58.dp)
  }

  // Material counts the label inside an empty field as large; at 200% that used to differ by 5 dp
  @Test
  @Config(fontScale = 2f)
  fun at200PercentFont_everyBoxIsAsTall() {
    setForm()
    val bounds = composeTestRule.onNodeWithTag(FORM.first().tag).getUnclippedBoundsInRoot()

    assertEveryBoxIs(bounds.bottom - bounds.top)
  }

  private companion object {
    const val FIELD = "field"

    /** A field of a form: its test tag, its value and whether it has a helper. */
    data class FormField(val tag: String, val text: String, val helper: Boolean)

    // The first one has no helper, so its height is its box's
    val FORM =
        listOf(
            FormField("empty", "", helper = false),
            FormField("filled", "Value", helper = false),
            FormField("empty with a helper", "", helper = true),
            FormField("filled with a helper", "Value", helper = true),
        )
  }
}
