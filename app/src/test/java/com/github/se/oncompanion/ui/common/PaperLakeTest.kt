// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.github.se.oncompanion.ui.theme.Horizon
import com.github.se.oncompanion.ui.theme.HorizonDawnBottomDark
import com.github.se.oncompanion.ui.theme.HorizonDawnBottomLight
import com.github.se.oncompanion.ui.theme.HorizonDawnTopDark
import com.github.se.oncompanion.ui.theme.HorizonDawnTopLight
import com.github.se.oncompanion.ui.theme.HorizonDayBottomDark
import com.github.se.oncompanion.ui.theme.HorizonDayBottomLight
import com.github.se.oncompanion.ui.theme.HorizonDayTopDark
import com.github.se.oncompanion.ui.theme.HorizonDayTopLight
import com.github.se.oncompanion.ui.theme.HorizonDuskBottomDark
import com.github.se.oncompanion.ui.theme.HorizonDuskBottomLight
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
        PaperLakeHeader(LocalTime.parse(time), Modifier.testTag(HEADER)) { Text(TITLE) }
      }
    }
  }

  /** The colour of the sky, in the header's top-left corner. */
  /** The colour at ([x], [y]) dp in the node tagged [tag]. */
  private fun pixel(tag: String, x: Float, y: Float): Color {
    val density = compose.density.density
    return compose
        .onNodeWithTag(tag)
        .captureToImage()
        .toPixelMap()[(x * density).toInt(), (y * density).toInt()]
  }

  /** The colour at ([x], [y]) dp in the header. */
  private fun colorAt(x: Float, y: Float) = pixel(HEADER, x, y)

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
        PaperLakeHeader(LocalTime.NOON, Modifier.testTag(HEADER)) {
          Box(Modifier.height(300.dp).width(10.dp))
        }
      }
    }

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(300.dp)
  }

  @Test
  fun skyAndLake_followTheTimeOfDay_inBothThemes() {
    // The sky in the top-left corner and the far water just under the mountains' foot, in the
    // header (210 dp on this 411 dp window); the theme's Horizon gives both, light and dark
    val cases =
        listOf(
            Triple("08:00", false, Horizon(HorizonDawnTopLight, HorizonDawnBottomLight)),
            Triple("14:00", false, Horizon(HorizonDayTopLight, HorizonDayBottomLight)),
            Triple("21:00", false, Horizon(HorizonDuskTopLight, HorizonDuskBottomLight)),
            Triple("08:00", true, Horizon(HorizonDawnTopDark, HorizonDawnBottomDark)),
            Triple("14:00", true, Horizon(HorizonDayTopDark, HorizonDayBottomDark)),
            Triple("21:00", true, Horizon(HorizonDuskTopDark, HorizonDuskBottomDark)),
        )
    cases.forEach { (time, dark, horizon) ->
      setHeaderFresh(time, darkTheme = dark)
      val theme = if (dark) "dark" else "light"
      assertSameColor("sky at $time, $theme", horizon.top, skyColor())
      assertSameColor("lake at $time, $theme", horizon.bottom, colorAt(20f, FAR_WATER_Y))
    }
  }

  @Test
  fun bandAndScene_paintTheirWaterInTheLakeColour() {
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        Column {
          PaperLakeBand(LocalTime.parse("08:00"), Modifier.testTag("band"))
          PaperLakeScene(LocalTime.parse("21:00"), Modifier.testTag("scene").width(390.dp))
        }
      }
    }

    // The band's waterline is its bottom 4 dp; the scene's far water starts at 336 of 470 dp
    assertSameColor("band waterline at dawn", HorizonDawnBottomLight, pixel("band", 20f, 98f))
    assertSameColor("scene water at dusk", HorizonDuskBottomLight, pixel("scene", 20f, 360f))
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
        PaperLakeScene(LocalTime.parse("14:00"), Modifier.testTag(HEADER).width(390.dp))
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
        PaperLakeBand(LocalTime.parse("14:00"), Modifier.testTag(HEADER))
      }
    }

    compose.onNodeWithTag(HEADER).assertHeightIsEqualTo(100.dp)
    assertSameColor("band sky by day", HorizonDayTopLight, skyColor())
  }

  @Test
  fun withOnlyATime_eachSizeShowsItsSky() {
    // The plainest use: no modifier and no content
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        Column {
          Box(Modifier.testTag("header")) { PaperLakeHeader(LocalTime.NOON) }
          Box(Modifier.testTag("band")) { PaperLakeBand(LocalTime.NOON) }
          Box(Modifier.testTag("scene").width(390.dp)) { PaperLakeScene(LocalTime.NOON) }
        }
      }
    }

    listOf("header", "band", "scene").forEach { tag ->
      val sky = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[4, 4]
      assertSameColor("$tag sky at noon", HorizonDayTopLight, sky)
    }
  }

  @Test
  // Tall enough for all three sizes at once
  @Config(qualifiers = "w411dp-h2400dp-xhdpi")
  fun whenTheScreenRecomposes_theSkyFollowsTheTime_notTheTitle() {
    val title = mutableStateOf("Good morning, Anne")
    val time = mutableStateOf("08:00")
    compose.setContent {
      OncompanionTheme(darkTheme = false) {
        Column {
          val at = LocalTime.parse(time.value)
          PaperLakeHeader(at, Modifier.testTag("header")) { Text(title.value) }
          PaperLakeBand(at, Modifier.testTag("band")) { Text(title.value) }
          PaperLakeScene(at, Modifier.testTag("scene").width(390.dp))
        }
      }
    }
    fun sky(tag: String) = compose.onNodeWithTag(tag).captureToImage().toPixelMap()[4, 4]

    title.value = "Good afternoon, Anne"
    compose.waitForIdle()
    listOf("header", "band", "scene").forEach {
      assertSameColor("$it after a new title", HorizonDawnTopLight, sky(it))
    }

    time.value = "21:00"
    compose.waitForIdle()
    listOf("header", "band", "scene").forEach {
      assertSameColor("$it at 21:00", HorizonDuskTopLight, sky(it))
    }
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
          PaperLakeHeader(LocalTime.parse(time.value), Modifier.testTag(HEADER))
        }
      }
    }
    compose.waitForIdle()
  }

  private companion object {
    const val HEADER = "paper_lake"
    // In the header's far water band: under the foothills, above the middle band
    const val FAR_WATER_Y = 165f
    const val TITLE = "Good morning, Anne"
  }
}
