// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.ui.theme.HorizonDawnTopLight
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Wider than the lake, so it is drawn at the Figma's size and its units map to pixels
@Config(qualifiers = "w411dp-h891dp")
class VoiceLakeTest {

  @get:Rule val compose = createComposeRule()

  private var sun by mutableStateOf(VoiceSun.HIDDEN)
  private var level by mutableStateOf(0f)

  /** The sun's colour (DESIGN.md), as drawn by voice_sun. */
  private val sunColor = Color(0xFFFBF8F4)

  private fun show() {
    compose.mainClock.autoAdvance = false
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        // At the Figma's size, so a pixel is a unit of the lake (at the test's density)
        VoiceLake(
            sun,
            level,
            Modifier.size(390.dp, 270.dp).testTag(LAKE),
            time = LocalTime.of(9, 0),
        )
      }
    }
  }

  /**
   * Applies what the test changed, then lets every animation of the lake finish (the breathing
   * never does, but stays small).
   */
  private fun settle() {
    compose.waitForIdle()
    compose.mainClock.advanceTimeBy(3_000)
  }

  /** The colour of the lake at ([x], [y]) in the Figma's units. */
  private fun colorAt(x: Float, y: Float): Color {
    val image = compose.onNodeWithTag(LAKE).captureToImage()
    val scale = image.width / 390f
    return image.toPixelMap()[(x * scale).toInt(), (y * scale).toInt()]
  }

  private fun Color.isCloseTo(other: Color) =
      abs(red - other.red) < 0.03f &&
          abs(green - other.green) < 0.03f &&
          abs(blue - other.blue) < 0.03f

  /** Beside the mic glyph, inside the sun wherever it is placed (even settled). */
  private fun onTheSun() = colorAt(262f + 20f, 156f)

  @Test
  fun theSun_showsOnlyWhenTheAppCanListen() {
    show()
    for ((state, visible) in
        listOf(
            VoiceSun.LISTENING to true,
            VoiceSun.RESTING to true,
            VoiceSun.SETTLED to true,
            VoiceSun.HIDDEN to false,
        )) {
      sun = state
      settle()
      val color = onTheSun()
      if (visible) assertTrue("$state: $color", color.isCloseTo(sunColor))
      // No sun: the dawn sky
      else assertTrue("$state: $color", color.isCloseTo(HorizonDawnTopLight))
    }
  }

  @Test
  fun theHalos_openWithTheVoice() {
    show()
    sun = VoiceSun.LISTENING
    settle()
    // Outside the sun and its first halo, inside the widest
    val quiet = colorAt(262f + 54f, 156f)

    level = 1f
    settle()
    val loud = colorAt(262f + 54f, 156f)

    // White paper over the sky: louder is lighter
    assertTrue("quiet $quiet, loud $loud", loud.blue > quiet.blue + 0.02f)
  }

  @Test
  fun theLake_isDecorative() {
    show()
    sun = VoiceSun.LISTENING
    settle()
    compose
        .onNodeWithTag(LAKE)
        .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
  }

  private companion object {
    const val LAKE = "lake"
  }
}
