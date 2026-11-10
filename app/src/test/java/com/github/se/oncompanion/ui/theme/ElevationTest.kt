// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElevationTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun elevation1_matchesFigmaEffectStyle() {
    // Figma effect style "Oncompanion/Elevation 1": 0 1 2 at 6% and 0 6 18 at 8%, ink #1F2930
    val ink = Color(0xFF1F2930)
    val expected =
        listOf(
            Shadow(radius = 2.dp, color = ink, offset = DpOffset(0.dp, 1.dp), alpha = 0.06f),
            Shadow(radius = 18.dp, color = ink, offset = DpOffset(0.dp, 6.dp), alpha = 0.08f),
        )
    assertEquals(expected, Elevation1)
  }

  @Test
  fun elevation1_drawsAroundItsElement() {
    composeTestRule.setContent {
      OncompanionTheme { Box(Modifier.size(48.dp).elevation1(Shapes.large).testTag("raised")) }
    }

    composeTestRule.onNodeWithTag("raised").assertIsDisplayed()
  }
}
