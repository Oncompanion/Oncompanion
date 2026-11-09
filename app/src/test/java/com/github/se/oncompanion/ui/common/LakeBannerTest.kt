// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.SMALL_PHONE
import com.github.se.oncompanion.ui.assertNoWordIsCut
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.assertTextStyle
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.lakeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** [LakeBanner] as the Figma "Inline banner" (Shared patterns), tone by tone. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeBannerTest {

  @get:Rule val composeTestRule = createComposeRule()

  private lateinit var colors: ColorScheme
  private lateinit var typography: Typography
  private var primaryText = Color.Unspecified
  private var retries = 0

  /** One banner per tone, tagged with its tone's name, with an [action] when [withAction]. */
  private fun setContent(
      message: String = "Couldn't refresh the events.",
      withAction: Boolean = true,
      action: String = "Retry",
  ) {
    composeTestRule.setContent {
      OncompanionTheme {
        colors = MaterialTheme.colorScheme
        typography = MaterialTheme.typography
        primaryText = MaterialTheme.lakeColors.primaryText
        Surface(color = MaterialTheme.colorScheme.surface) {
          Column {
            for (tone in LakeBannerTone.entries) {
              LakeBanner(
                  message = "$message ${tone.name}",
                  tone = tone,
                  actionLabel = if (withAction) "$action ${tone.name}" else null,
                  onAction = { retries++ },
                  actionTestTag = "${tone.name} action",
                  modifier = Modifier.testTag(tone.name),
              )
            }
          }
        }
      }
    }
  }

  private fun banner(tone: LakeBannerTone) = composeTestRule.onNodeWithTag(tone.name)

  private fun bounds(tone: LakeBannerTone) = banner(tone).getUnclippedBoundsInRoot()

  /** Each tone's container and text colors, from the Figma. */
  private fun figmaColors(tone: LakeBannerTone): Pair<Color, Color> =
      when (tone) {
        LakeBannerTone.Neutral -> colors.surfaceContainerHigh to colors.onSurface
        LakeBannerTone.Waiting -> colors.secondaryContainer to colors.onSecondaryContainer
      }

  @Test
  fun eachTone_isA48dpBox_withMediumCorners_inItsFigmaColors() {
    setContent()

    for (tone in LakeBannerTone.entries) {
      val (container, content) = figmaColors(tone)
      banner(tone).assertHeightIsEqualTo(48.dp)
      composeTestRule
          .onNodeWithText("Couldn't refresh the events. ${tone.name}", useUnmergedTree = true)
          .assertTextStyle(typography.bodyMedium, content)
      // Left of the icon, in the 12 dp padding; the very corner is rounded off
      assertSameColor("$tone container", container, banner(tone).colorAt(6.dp, 24.dp))
      assertSameColor("$tone corner", colors.surface, banner(tone).colorAt(1.dp, 1.dp))
    }
  }

  @Test
  fun theMessage_starts12dpAfterA24dpIcon_12dpFromTheEdge() {
    setContent()

    for (tone in LakeBannerTone.entries) {
      val text =
          composeTestRule
              .onNodeWithText("Couldn't refresh the events. ${tone.name}", useUnmergedTree = true)
              .getUnclippedBoundsInRoot()
      assertEquals(12.dp + 24.dp + 12.dp, text.left - bounds(tone).left)
    }
  }

  @Test
  fun theAction_isALabelLargeLink_8dpFromTheEnd_atLeast48dpTall_andCallsBack() {
    setContent()

    for (tone in LakeBannerTone.entries) {
      val action = composeTestRule.onNodeWithTag("${tone.name} action")
      composeTestRule
          .onNodeWithText("Retry ${tone.name}", useUnmergedTree = true)
          .assertTextStyle(typography.labelLarge, primaryText)
      action.assertHeightIsAtLeast(48.dp)
      assertEquals(8.dp, bounds(tone).right - action.getUnclippedBoundsInRoot().right)
      action.performClick()
    }
    assertEquals(LakeBannerTone.entries.size, retries)
  }

  @Test
  fun withoutAnAction_showsOnlyTheMessage() {
    setContent(withAction = false)

    composeTestRule.onNodeWithTag("Neutral action").assertDoesNotExist()
    composeTestRule.onNodeWithText("Retry Neutral").assertDoesNotExist()
  }

  // A long message, or a large font, wraps and the banner grows rather than cutting it
  @Test
  @Config(fontScale = 2f)
  fun at200PercentFont_theBannerGrowsToKeepTheWholeMessage() {
    setContent(message = "Saved on your phone. It will be sent when you're back online.")

    for (tone in LakeBannerTone.entries) {
      val box = bounds(tone)
      val message =
          composeTestRule
              .onNodeWithText(
                  "Saved on your phone. It will be sent when you're back online. ${tone.name}",
                  useUnmergedTree = true,
              )
              .getUnclippedBoundsInRoot()
      assertTrue("$tone grows", box.bottom - box.top > 48.dp)
      assertTrue("$tone message inside", message.top >= box.top && message.bottom <= box.bottom)
    }
  }

  // On a narrow phone at 200%, a long action beside the message would leave it a few letters a line
  @Test
  @Config(qualifiers = SMALL_PHONE, fontScale = 2f)
  fun aLongAction_atLargeFont_leavesTheMessageAndTheActionReadable() {
    setContent(action = "Try again later")

    for (tone in LakeBannerTone.entries) {
      for (text in
          listOf("Couldn't refresh the events. ${tone.name}", "Try again later ${tone.name}")) {
        composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertNoWordIsCut()
      }
      val action = composeTestRule.onNodeWithTag("${tone.name} action").getUnclippedBoundsInRoot()
      val box = bounds(tone)
      assertTrue("$tone action inside", action.left >= box.left && action.right <= box.right)
    }
  }
}
