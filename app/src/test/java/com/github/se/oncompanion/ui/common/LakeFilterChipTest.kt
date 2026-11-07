// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.github.se.oncompanion.resources.C
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

  private fun setContent(selected: Boolean, enabled: Boolean = true) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        Surface(color = MaterialTheme.colorScheme.surface) {
          LakeFilterChip(
              selected = selected,
              onClick = { clicks++ },
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
  fun unselected_isA48dpWhiteChip_withA1dpOutline_andNoCheck() {
    setContent(selected = false)

    chip().assertHeightIsEqualTo(48.dp).assertIsNotSelected()
    composeTestRule
        .onNodeWithText("Fatigue", useUnmergedTree = true)
        .assertTextStyle(typography.labelLarge, colors.onSurface)
    composeTestRule
        .onNodeWithTag(C.Tag.filter_chip_check, useUnmergedTree = true)
        .assertDoesNotExist()
    assertSameColor("border", colors.outline, colorAcross(0.5.dp))
    assertSameColor("inside", colors.surfaceContainerLowest, colorAcross(2.dp))
  }

  @Test
  fun selected_isDawn_withACheckBeforeItsLabel_andNoBorder() {
    setContent(selected = true)

    chip().assertHeightIsEqualTo(48.dp).assertIsSelected()
    composeTestRule
        .onNodeWithText("Fatigue", useUnmergedTree = true)
        .assertTextStyle(typography.labelLarge, colors.onSecondaryContainer)
    val check =
        composeTestRule
            .onNodeWithTag(C.Tag.filter_chip_check, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
    val label =
        composeTestRule.onNodeWithText("Fatigue", useUnmergedTree = true).getUnclippedBoundsInRoot()
    assertEquals(20.dp, check.right - check.left)
    assertEquals(8.dp, label.left - check.right)
    assertSameColor("edge", colors.secondaryContainer, colorAcross(0.5.dp))
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

  private companion object {
    const val CHIP = "chip"
  }
}
