// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.buttonLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class SaveButtonTest {

  @get:Rule val composeTestRule = createComposeRule()

  private var clicks = 0
  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography

  /** The button in its bottom bar, on the theme's surface, as a form shows it. */
  private fun setContent(enabled: Boolean = true, isSaving: Boolean = false) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        Surface(color = MaterialTheme.colorScheme.surface) {
          Box(Modifier.testTag(BAR)) {
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
      }
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

  // ----- Figma: "Button", Style=Filled, and the bottom bar of a form ("Log symptoms – Filled")
  // -----

  @Test
  fun isA56dpButton_withTheFigmaLabel() {
    setContent()

    composeTestRule.onNodeWithTag(BUTTON).assertHeightIsEqualTo(56.dp)
    composeTestRule
        .onNodeWithText("Save", useUnmergedTree = true)
        .assertTextStyle(typography.buttonLabel, colors.onPrimary)
  }

  @Test
  fun sitsInItsBar_16dpFromTheSides_12dpBelowItsTop_24dpAboveItsBottom() {
    setContent()

    val bar = composeTestRule.onNodeWithTag(BAR).getUnclippedBoundsInRoot()
    val button = composeTestRule.onNodeWithTag(BUTTON).getUnclippedBoundsInRoot()
    assertEquals(16.dp, button.left - bar.left)
    assertEquals(16.dp, bar.right - button.right)
    assertEquals(12.dp, button.top - bar.top)
    assertEquals(24.dp, bar.bottom - button.bottom)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun disabled_isTheFilledButtonAt38PercentOpacity() {
    setContent(enabled = false)

    // Left of the label, inside the rounded end: the teal at 38% over the surface
    val drawn = composeTestRule.onNodeWithTag(BUTTON).colorAt(10.dp, 28.dp)
    assertSameColor("container", lerp(colors.surface, colors.primary, 0.38f), drawn)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun saving_keepsTheFullPrimaryColor_soTheSpinnerShowsTheTapWasTaken() {
    setContent(enabled = false, isSaving = true)

    val drawn = composeTestRule.onNodeWithTag(BUTTON).colorAt(10.dp, 28.dp)
    assertSameColor("container", colors.primary, drawn)
  }

  private companion object {
    const val BAR = "bar"
    const val BUTTON = "button"
    const val SAVING = "saving"
  }
}
