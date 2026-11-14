// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** [LakeFilterChip] as the Figma "Filter chip" (Brand & Theme). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeFilterChipTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var clicks = 0

  private fun setContent(
      selected: Boolean,
      enabled: Boolean = true,
      onClick: (() -> Unit)? = { clicks++ },
  ) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        Surface(color = MaterialTheme.colorScheme.surface) {
          LakeFilterChip(
              selected = selected,
              onClick = onClick,
              label = "Fatigue",
              enabled = enabled,
              modifier = Modifier.testTag(CHIP),
          )
        }
      }
    }
  }

  private fun chip() = composeTestRule.onNodeWithTag(CHIP)

  /** The color halfway down the chip, [x] from its left edge. */
  private fun colorAcross(x: Dp) =
      chip().colorAt(x, chip().getUnclippedBoundsInRoot().let { (it.bottom - it.top) / 2 })

  @Test
  fun unselected_isA48dpWhiteChip_withA1dpOutline() {
    setContent(selected = false)

    chip().assertHeightIsEqualTo(48.dp).assertIsNotSelected()
    composeTestRule
        .onNodeWithText("Fatigue", useUnmergedTree = true)
        .assertTextStyle(typography.labelLarge, colors.onSurface)
    assertSameColor("border", colors.outline, colorAcross(0.5.dp))
    assertSameColor("inside", colors.surfaceContainerLowest, colorAcross(2.dp))
  }

  @Test
  fun selected_isDawn_withoutBorder_andItsLabel16dpFromEachSide() {
    setContent(selected = true)

    chip().assertHeightIsEqualTo(48.dp).assertIsSelected()
    composeTestRule
        .onNodeWithText("Fatigue", useUnmergedTree = true)
        .assertTextStyle(typography.labelLarge, colors.onSecondaryContainer)
    val chipBounds = chip().getUnclippedBoundsInRoot()
    val label =
        composeTestRule.onNodeWithText("Fatigue", useUnmergedTree = true).getUnclippedBoundsInRoot()
    assertEquals(16.dp, label.left - chipBounds.left)
    assertEquals(16.dp, chipBounds.right - label.right)
    assertSameColor("edge", colors.secondaryContainer, colorAcross(0.5.dp))
  }

  // A chip that grew when picked would push the chips after it along, or onto the next row
  @Test
  fun pickingAndUnpicking_keepsItsWidth() {
    var selected by mutableStateOf(false)
    composeTestRule.setContent {
      OncompanionTheme {
        LakeFilterChip(
            selected = selected,
            onClick = { selected = !selected },
            label = "Fatigue",
            modifier = Modifier.testTag(CHIP),
        )
      }
    }
    fun width() = chip().getUnclippedBoundsInRoot().let { it.right - it.left }
    val unpicked = width()

    for (expectedSelected in listOf(true, false)) {
      chip().performClick()
      assertEquals(expectedSelected, selected)
      assertEquals(unpicked.value, width().value, 0.5f)
    }
  }

  @Test
  fun tapping_reportsIt() {
    setContent(selected = false)
    chip().performClick()
    assertEquals(1, clicks)
  }

  @Test
  fun disabled_cantBeTapped() {
    setContent(selected = false, enabled = false)

    chip().assertIsNotEnabled().performClick()

    assertEquals(0, clicks)
  }

  // A detail shows what was logged: the same colours, but nothing to tap
  @Test
  fun readOnly_isDawn_andCantBeTappedNorHeardAsACheckbox() {
    setContent(selected = true, onClick = null)

    chip().assertHeightIsEqualTo(48.dp)
    // The label sits 16 dp from each side, as on a chip that can be picked
    val chipBounds = chip().getUnclippedBoundsInRoot()
    val label =
        composeTestRule.onNodeWithText("Fatigue", useUnmergedTree = true).getUnclippedBoundsInRoot()
    assertEquals(16.dp, label.left - chipBounds.left)
    assertSameColor("edge", colors.secondaryContainer, colorAcross(0.5.dp))
    chip()
        .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
  }

  // Material's chip was a checkbox for TalkBack; a single choice reads as a radio button
  @Test
  fun screenReaders_hearACheckbox_orARadioButtonForASingleChoice() {
    composeTestRule.setContent {
      OncompanionTheme {
        LakeFilterChip(
            selected = false,
            onClick = {},
            label = "Fatigue",
            modifier = Modifier.testTag(CHIP),
        )
        LakeFilterChip(
            selected = false,
            onClick = {},
            label = "Treatment",
            role = Role.RadioButton,
            modifier = Modifier.testTag(RADIO),
        )
      }
    }

    for ((tag, role) in listOf(CHIP to Role.Checkbox, RADIO to Role.RadioButton)) {
      composeTestRule
          .onNodeWithTag(tag)
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))
    }
  }

  private companion object {
    const val CHIP = "chip"
    const val RADIO = "radio"
  }
}
