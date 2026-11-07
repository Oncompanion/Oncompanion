// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.theme.HorizonDawnTopDark
import com.github.se.oncompanion.ui.theme.HorizonDawnTopLight
import com.github.se.oncompanion.ui.theme.HorizonDayTopLight
import com.github.se.oncompanion.ui.theme.HorizonDuskTopDark
import com.github.se.oncompanion.ui.theme.HorizonDuskTopLight
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// A portrait phone: Robolectric's default window (470 dp tall) would count as landscape
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PaperLakeTest {

  @get:Rule val compose = createComposeRule()

  // ----- Time of day -----

  @Test
  fun eachTime_hasTheLightOfItsPartOfTheDay() {
    val cases =
        mapOf(
            "00:00" to TimeOfDay.DUSK,
            "04:59" to TimeOfDay.DUSK,
            "05:00" to TimeOfDay.DAWN,
            "10:59" to TimeOfDay.DAWN,
            "11:00" to TimeOfDay.DAY,
            "17:59" to TimeOfDay.DAY,
            "18:00" to TimeOfDay.DUSK,
            "23:59" to TimeOfDay.DUSK,
        )
    cases.forEach { (time, expected) ->
      assertEquals(time, expected, timeOfDayAt(LocalTime.parse(time)))
    }
  }

  // ----- Header and band -----

  private fun setHeader(time: String = "08:00", darkTheme: Boolean = false) {
    compose.setContent {
      OncompanionTheme(darkTheme = darkTheme) {
        PaperLakeHeader(Modifier.testTag(HEADER), time = LocalTime.parse(time)) { Text(TITLE) }
      }
    }
  }

  /** The colour of the sky, in the header's top-left corner. */
  private fun skyColor(): Color {
    val pixels = compose.onNodeWithTag(HEADER).captureToImage().toPixelMap()
    return pixels[4, 4]
  }

  private fun assertSameColor(message: String, expected: Color, actual: Color) {
    val close =
        abs(expected.red - actual.red) < 0.02f &&
            abs(expected.green - actual.green) < 0.02f &&
            abs(expected.blue - actual.blue) < 0.02f
    assertTrue("$message: expected $expected, was $actual", close)
  }

  @Test
  fun header_isTheFigmaHeight_inPortrait() {
    setHeader()

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(210.dp)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun header_becomesTheBand_inLandscape() {
    setHeader()

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(100.dp)
    compose.onNodeWithText(TITLE).assertExists()
  }

  @Test
  @Config(fontScale = 2f)
  fun header_becomesTheBand_atALargeFontScale() {
    setHeader()

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(100.dp)
  }

  @Test
  @Config(fontScale = 1.15f)
  fun header_staysTall_atASlightlyLargerFont() {
    setHeader()

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(210.dp)
  }

  @Test
  fun header_growsWithATallTitle() {
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        PaperLakeHeader(Modifier.testTag(HEADER), time = LocalTime.NOON) {
          Box(Modifier.height(300.dp).width(10.dp))
        }
      }
    }

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(300.dp)
  }

  @Test
  fun sky_followsTheTimeOfDay_inTheLightTheme() {
    mapOf(
            "08:00" to HorizonDawnTopLight,
            "14:00" to HorizonDayTopLight,
            "21:00" to HorizonDuskTopLight,
        )
        .forEach { (time, sky) ->
          setHeaderFresh(time, darkTheme = false)
          assertSameColor("sky at $time", sky, skyColor())
        }
  }

  @Test
  @Config(qualifiers = "night")
  fun sky_followsTheTimeOfDay_inTheDarkTheme() {
    mapOf("08:00" to HorizonDawnTopDark, "21:00" to HorizonDuskTopDark).forEach { (time, sky) ->
      setHeaderFresh(time, darkTheme = true)
      assertSameColor("sky at $time", sky, skyColor())
    }
  }

  @Test
  fun illustration_isHiddenFromTalkBack_onlyTheContentIsRead() {
    setHeader()

    assertEquals(
        0,
        compose
            .onAllNodes(hasContentDescription("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size,
    )
    compose.onNodeWithText(TITLE).assertExists()
  }

  @Test
  fun scene_fillsTheWidth_underTheSkyOfTheTime() {
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        PaperLakeScene(Modifier.testTag(HEADER).width(390.dp), time = LocalTime.parse("14:00"))
      }
    }

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(470.dp)
    assertSameColor("scene sky by day", HorizonDayTopLight, skyColor())
    assertEquals(
        0,
        compose
            .onAllNodes(hasContentDescription("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size,
    )
  }

  @Test
  fun band_isTheFigmaHeight_evenInPortrait() {
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        PaperLakeBand(Modifier.testTag(HEADER), time = LocalTime.parse("14:00"))
      }
    }

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(100.dp)
    assertSameColor("band sky by day", HorizonDayTopLight, skyColor())
  }

  // setContent can only be called once per test: the time is swapped in a state instead
  private val time = mutableStateOf("08:00")
  private val dark = mutableStateOf(false)
  private var started = false

  private fun setHeaderFresh(newTime: String, darkTheme: Boolean) {
    time.value = newTime
    dark.value = darkTheme
    if (!started) {
      started = true
      compose.setContent {
        OncompanionTheme(darkTheme = dark.value) {
          PaperLakeHeader(Modifier.testTag(HEADER), time = LocalTime.parse(time.value))
        }
      }
    }
    compose.waitForIdle()
  }

  private companion object {
    const val HEADER = "paper_lake"
    const val TITLE = "Good morning, Anne"
  }
}
