// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression tests for visibility checks used by the screen layout tests. */
@RunWith(AndroidJUnit4::class)
class KeyboardInsetsTest {
  @get:Rule val composeTestRule = createComposeRule()

  /** A constrained parent can leave a semantics node present while giving it no drawable area. */
  @Test
  fun fullyVisible_requiresPositiveSizeAndUnclippedBounds() {
    val cases =
        listOf(
            VisibilityCase("fully shown", 80, 48, 80, 48, true),
            VisibilityCase("zero height", 80, 0, 80, 0, false),
            VisibilityCase("zero width", 0, 48, 0, 48, false),
            VisibilityCase("zero size", 0, 0, 0, 0, false),
            VisibilityCase("vertical clipping", 80, 48, 80, 24, false),
            VisibilityCase("horizontal clipping", 80, 48, 40, 48, false),
        )
    val current = mutableStateOf(cases.first())
    composeTestRule.setContent {
      val case = current.value
      // Fixed pixel constraints make zero-size and clipped fixtures independent of screen density.
      Layout(
          content = {
            Layout(
                content = { Box(Modifier.testTag(TARGET)) },
                modifier = Modifier.clipToBounds(),
            ) { measurables, _ ->
              val child = measurables.single().measure(Constraints.fixed(case.width, case.height))
              layout(case.visibleWidth, case.visibleHeight) { child.place(0, 0) }
            }
          }
      ) { measurables, _ ->
        val child = measurables.single().measure(Constraints())
        layout(100, 100) { child.place(0, 0) }
      }
    }

    for (case in cases) {
      composeTestRule.runOnIdle { current.value = case }
      val target = composeTestRule.onNodeWithTag(TARGET)
      val node = target.fetchSemanticsNode()
      // Check the fixture first so a missing node or an ineffective clip cannot satisfy the test.
      assertEquals(case.name, case.width, node.size.width)
      assertEquals(case.name, case.height, node.size.height)
      // Compose reports an empty rectangle on both axes when either dimension is zero.
      val empty = case.width == 0 || case.height == 0
      assertEquals(
          case.name,
          if (empty) 0f else case.visibleWidth.toFloat(),
          node.boundsInRoot.width,
          0f,
      )
      assertEquals(
          case.name,
          if (empty) 0f else case.visibleHeight.toFloat(),
          node.boundsInRoot.height,
          0f,
      )
      if (case.fullyVisible) {
        target.assertIsFullyVisible()
      } else {
        assertThrows(case.name, AssertionError::class.java) { target.assertIsFullyVisible() }
      }
    }
  }

  private data class VisibilityCase(
      val name: String,
      val width: Int,
      val height: Int,
      val visibleWidth: Int,
      val visibleHeight: Int,
      val fullyVisible: Boolean,
  )

  private companion object {
    const val TARGET = "visibility_target"
  }
}
