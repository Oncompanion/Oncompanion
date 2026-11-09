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
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LakeMessageState] as the Figma "Message state" (Shared patterns). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeMessageStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var retries = 0
  private var dark by mutableStateOf(false)

  private fun setContent(message: String? = LINE, actionLabel: String? = null) {
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        Surface(color = MaterialTheme.colorScheme.surface) {
          LakeMessageState(
              title = TITLE,
              message = message,
              actionLabel = actionLabel,
              onAction = { retries++ },
              actionTestTag = ACTION,
              modifier = Modifier.testTag(STATE),
          )
        }
      }
    }
  }

  private fun bounds(text: String) =
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot()

  @Test
  fun startsWithA64dpRoundTile_onSurfaceContainerHigh_inBothThemes_24dpAboveTheTitle() {
    setContent()

    val tile = composeTestRule.onNodeWithTag(C.Tag.message_state_icon)
    tile.assertWidthIsEqualTo(64.dp).assertHeightIsEqualTo(64.dp)
    for (theme in listOf(false, true)) {
      dark = theme
      composeTestRule.waitForIdle()
      // Inside the circle, left of the icon; the tile's corner is outside the circle
      assertSameColor("tile, dark $theme", colors.surfaceContainerHigh, tile.colorAt(8.dp, 32.dp))
      assertSameColor("outside, dark $theme", colors.surface, tile.colorAt(2.dp, 2.dp))
      composeTestRule
          .onNodeWithText(TITLE, useUnmergedTree = true)
          .assertTextStyle(typography.titleLarge, colors.onSurface)
    }
    assertEquals(24.dp, bounds(TITLE).top - tile.getUnclippedBoundsInRoot().bottom)
  }

  @Test
  fun titleAndLine_useTheFigmaStyles_centered() {
    setContent()

    val state = composeTestRule.onNodeWithTag(STATE).getUnclippedBoundsInRoot()
    val center = (state.left + state.right) / 2
    val cases =
        listOf(
            Triple(TITLE, typography.titleLarge, colors.onSurface),
            Triple(LINE, typography.bodyMedium, colors.onSurfaceVariant),
        )
    for ((text, style, color) in cases) {
      composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertTextStyle(style, color)
      val box = bounds(text)
      assertEquals(text, center.value, ((box.left + box.right) / 2).value, 0.5f)
    }
  }

  // Many of the Figma's states are only a title, e.g. "Couldn't load your circle." and Retry
  @Test
  fun withoutALine_theActionComes20dpBelowTheTitle() {
    setContent(message = null, actionLabel = "Retry")

    val action = composeTestRule.onNodeWithTag(ACTION).getUnclippedBoundsInRoot()
    assertEquals(20.dp, action.top - bounds(TITLE).bottom)
  }

  @Test
  fun theAction_isAFilledButton_20dpBelowTheLine_andCallsBack() {
    setContent(actionLabel = "Retry")

    val action = composeTestRule.onNodeWithTag(ACTION)
    action.assertHeightIsEqualTo(56.dp)
    assertEquals(20.dp, action.getUnclippedBoundsInRoot().top - bounds(LINE).bottom)
    // Left of the label, inside the rounded end: the Filled button's teal
    assertSameColor("button", colors.primary, action.colorAt(10.dp, 28.dp))
    action.performClick()
    assertEquals(1, retries)
  }

  @Test
  fun withoutAnAction_showsNoButton() {
    setContent()

    composeTestRule.onNodeWithTag(ACTION).assertDoesNotExist()
  }

  // A phone held sideways at 200% has room for little more than the tile: Retry must stay reachable
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE, fontScale = 2f)
  fun at200PercentFont_inShortLandscape_theActionIsFullyVisible() {
    setContent(actionLabel = "Retry")

    composeTestRule.onNodeWithTag(ACTION).assertIsFullyVisible()
  }

  private companion object {
    const val STATE = "state"
    const val ACTION = "action"
    const val TITLE = "Couldn't load the events"
    const val LINE = "Please try again."
  }
}
