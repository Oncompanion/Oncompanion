// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.OutlineLight
import com.github.se.oncompanion.ui.theme.SecondaryContainerDark
import com.github.se.oncompanion.ui.theme.SecondaryContainerLight
import com.github.se.oncompanion.ui.theme.SurfaceContainerLowestLight
import com.github.se.oncompanion.ui.theme.TertiaryContainerLight
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LakeIllustrationsTest {

  @get:Rule val compose = createComposeRule()

  private fun setThemed(dark: Boolean = false, content: @Composable () -> Unit) {
    compose.setContent {
      OncompanionTheme(darkTheme = dark) { Column(Modifier.testTag(ROOT)) { content() } }
    }
  }

  /** The colour at ([x], [y]) dp of the content. */
  private fun colorAt(x: Float, y: Float): Color {
    val density = compose.density.density
    return compose
        .onNodeWithTag(ROOT)
        .captureToImage()
        .toPixelMap()[(x * density).toInt(), (y * density).toInt()]
  }

  private fun assertSameColor(message: String, expected: Color, actual: Color) {
    val close =
        abs(expected.red - actual.red) < 0.02f &&
            abs(expected.green - actual.green) < 0.02f &&
            abs(expected.blue - actual.blue) < 0.02f
    assertTrue("$message: expected $expected, was $actual", close)
  }

  @Test
  fun boatInitial_isTheFirstLetterInCapitals() {
    mapOf("Marc" to "M", "jules" to "J", "  sophie" to "S", "" to "", "   " to "").forEach {
        (name, initial) ->
      assertEquals("'$name'", initial, boatInitial(name))
    }
  }

  @Test
  fun ribbonLogo_isReadAsTheAppName() {
    setThemed { RibbonLogo() }

    compose.onNodeWithContentDescription("oncompanion").assertExists()
  }

  @Test
  fun illustrations_areHiddenFromTalkBack() {
    setThemed {
      EmptyLake()
      TwoBoats()
      CircleOnTheLake(listOf(CircleBoat("Marc", BoatTone.DAWN)), Modifier.width(200.dp))
      MemberBoat("Jules", BoatTone.PENDING)
    }

    assertEquals(
        0,
        compose
            .onAllNodes(hasContentDescription("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size,
    )
  }

  @Test
  fun memberBoat_sailTakesTheColourOfItsTone() {
    // The sail's middle, away from the initial, in the 35 × 37.5 frame drawn 48 dp tall
    val scale = 48f / 37.5f
    mapOf(
            BoatTone.DAWN to SecondaryContainerLight,
            BoatTone.DUSK to TertiaryContainerLight,
            BoatTone.PENDING to SurfaceContainerLowestLight,
        )
        .forEach { (tone, sail) ->
          showBoat(tone)
          assertSameColor("$tone sail", sail, colorAt(20f * scale, 24f * scale))
        }
  }

  @Test
  fun pendingBoat_hasAnOutlinedSailAndHull() {
    setThemed { MemberBoat("Jules", BoatTone.PENDING) }
    val scale = 48f / 37.5f

    // The hull, under the sail
    assertSameColor("pending hull", OutlineLight, colorAt(17.5f * scale, 34f * scale))
  }

  @Test
  @Config(qualifiers = "night")
  fun memberBoat_followsTheDarkTheme() {
    setThemed(dark = true) { MemberBoat("Marc", BoatTone.DAWN) }
    val scale = 48f / 37.5f

    assertSameColor("dark dawn sail", SecondaryContainerDark, colorAt(20f * scale, 24f * scale))
  }

  @Test
  fun circleOnTheLake_showsAtMostThreeBoats_inTheFigmaPlaces() {
    // 358 dp wide, so one Figma unit is one dp
    setThemed {
      CircleOnTheLake(
          listOf(
              CircleBoat("Marc", BoatTone.DAWN),
              CircleBoat("Sophie", BoatTone.DUSK),
              CircleBoat("Jules", BoatTone.PENDING),
              CircleBoat("Anne", BoatTone.DAWN),
          ),
          Modifier.width(358.dp),
      )
    }

    // Each sail's middle: left, right, then in front
    assertSameColor("left boat", SecondaryContainerLight, colorAt(54.5f + 20f, 79.67f + 24f))
    assertSameColor("right boat", TertiaryContainerLight, colorAt(268.5f + 20f, 79.67f + 24f))
    assertSameColor("front boat", SurfaceContainerLowestLight, colorAt(161.5f + 20f, 123.67f + 24f))
  }

  @Test
  fun circleOnTheLake_ignoresMembersAfterTheThird_andDrawsAnEmptyCircle() {
    val members = mutableStateOf(emptyList<CircleBoat>())
    setThemed { CircleOnTheLake(members.value, Modifier.width(358.dp)) }
    val boats =
        listOf(
            CircleBoat("Marc", BoatTone.DAWN),
            CircleBoat("Sophie", BoatTone.DUSK),
            CircleBoat("Jules", BoatTone.PENDING),
        )

    members.value = boats
    val three = compose.onNodeWithTag(ROOT).captureToImage().toPixelMap()
    members.value = boats + CircleBoat("Anne", BoatTone.DAWN) + CircleBoat("Luc", BoatTone.DUSK)
    val five = compose.onNodeWithTag(ROOT).captureToImage().toPixelMap()

    assertEquals(three.width, five.width)
    for (x in 0 until three.width step 4) for (y in 0 until three.height step 4) {
      assertEquals("pixel ($x, $y)", three[x, y], five[x, y])
    }
  }

  // setContent runs once per test, so the tone is swapped in a state
  private val tone = mutableStateOf(BoatTone.DAWN)
  private var started = false

  private fun showBoat(newTone: BoatTone) {
    tone.value = newTone
    if (!started) {
      started = true
      setThemed { MemberBoat("Marc", tone.value) }
    }
    compose.waitForIdle()
  }

  private companion object {
    const val ROOT = "lake_illustrations"
  }
}
