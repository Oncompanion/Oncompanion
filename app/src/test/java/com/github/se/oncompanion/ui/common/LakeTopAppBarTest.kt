// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.HorizonDawnTopLight
import com.github.se.oncompanion.ui.theme.HorizonDuskTopDark
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LakeTopAppBarTest {

  @get:Rule val compose = createComposeRule()

  // The time and theme the bar is drawn with, changed by the tests that go through several
  private var time by mutableStateOf("08:00")
  private var darkTheme by mutableStateOf(false)

  private fun setBar(onBack: () -> Unit = {}, onAction: () -> Unit = {}) {
    compose.setContent {
      OncompanionTheme(darkTheme = darkTheme) {
        LakeTopAppBar(
            title = TITLE,
            navigationIcon = {
              IconButton(onClick = onBack, modifier = Modifier.testTag(BACK)) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null)
              }
            },
            time = LocalTime.parse(time),
            actions = {
              IconButton(onClick = onAction, modifier = Modifier.testTag(ACTION)) { Text("A") }
            },
            modifier = Modifier.testTag(BAR),
        )
      }
    }
  }

  @Test
  fun bar_isTheFigmaBandHeight() {
    setBar()

    compose.onNodeWithTag(BAR).assertHeightIsEqualTo(100.dp)
  }

  @Test
  fun bar_showsTheTitle_andItsButtonsCallBack() {
    var backs = 0
    var actions = 0
    setBar(onBack = { backs++ }, onAction = { actions++ })

    compose.onNodeWithText(TITLE).assertExists()
    compose.onNodeWithTag(BACK).performClick()
    compose.onNodeWithTag(ACTION).performClick()

    assertEquals(1, backs)
    assertEquals(1, actions)
  }

  @Test
  fun bar_isTransparent_soTheSkyOfTheTimeShowsBehindTheTitle() {
    setBar()
    val cases =
        listOf(
            Triple("08:00", false, HorizonDawnTopLight),
            Triple("21:00", true, HorizonDuskTopDark),
        )
    for ((caseTime, caseDark, sky) in cases) {
      time = caseTime
      darkTheme = caseDark
      compose.waitForIdle()

      // Just right of the back button, in the bar's row, where no text is drawn
      val density = compose.density.density
      val pixel =
          compose
              .onNodeWithTag(BAR)
              .captureToImage()
              .toPixelMap()[(60 * density).toInt(), (4 * density).toInt()]
      val close =
          abs(sky.red - pixel.red) < 0.02f &&
              abs(sky.green - pixel.green) < 0.02f &&
              abs(sky.blue - pixel.blue) < 0.02f
      assertTrue("$caseTime, dark=$caseDark: expected $sky, was $pixel", close)
    }
  }

  private companion object {
    const val BAR = "lake_top_app_bar"
    const val BACK = "lake_top_app_bar_back"
    const val ACTION = "lake_top_app_bar_action"
    const val TITLE = "Fatigue, Nausea"
  }
}
