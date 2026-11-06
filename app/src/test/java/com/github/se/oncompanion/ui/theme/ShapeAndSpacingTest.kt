// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapeAndSpacingTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun themeShapes_matchDesignMdRadii() {
    lateinit var shapes: Shapes
    composeTestRule.setContent { OncompanionTheme { shapes = MaterialTheme.shapes } }
    composeTestRule.waitForIdle()

    // The "rounded" table of DESIGN.md (Figma variables "Radius/*")
    val cases =
        listOf(
            "Small" to (shapes.small to RoundedCornerShape(8.dp)),
            "Medium" to (shapes.medium to RoundedCornerShape(12.dp)),
            "Large" to (shapes.large to RoundedCornerShape(20.dp)),
            "Extra Large" to (shapes.extraLarge to RoundedCornerShape(28.dp)),
            "Full" to (shapes.full to CircleShape),
        )
    for ((name, shape) in cases) assertEquals(name, shape.second, shape.first)
  }

  @Test
  fun spacing_matchesDesignMdScale() {
    // The "spacing" table of DESIGN.md (Figma variables "Space/*")
    val cases =
        listOf(
            "xs" to (Spacing.xs to 4.dp),
            "sm" to (Spacing.sm to 8.dp),
            "md" to (Spacing.md to 12.dp),
            "lg" to (Spacing.lg to 16.dp),
            "lg-plus" to (Spacing.lgPlus to 20.dp),
            "xl" to (Spacing.xl to 24.dp),
            "xxl" to (Spacing.xxl to 32.dp),
        )
    for ((name, value) in cases) assertEquals(name, value.second, value.first)
  }
}
