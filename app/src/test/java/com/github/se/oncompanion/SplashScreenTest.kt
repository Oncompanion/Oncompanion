// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.theme.HorizonDawnTopLight
import com.github.se.oncompanion.ui.theme.PrimaryLight
import com.github.se.oncompanion.ui.theme.TertiaryLight
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The splash screen (Figma "US-2 / Splash – Signing you in"): the ribbon in the dawn lake, cut to a
 * 160 dp circle in the middle of the 288 dp that Android gives a splash icon without a background.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashScreenTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** The splash icon drawn on its 288 × 288 canvas, one pixel per dp. */
  private fun drawSplashIcon(): Bitmap {
    val icon = context.getDrawable(R.drawable.splash_icon)!!
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, SIZE, SIZE)
    icon.draw(Canvas(bitmap))
    return bitmap
  }

  /** The dawn lake's sky, ribbon and deep water, at Figma points shifted into the 160 dp circle. */
  private fun assertDawnLake(icon: Bitmap) {
    val cases =
        listOf(
            Triple("sky", HorizonDawnTopLight, 80 to 10),
            Triple("ribbon", TertiaryLight, 80 to 41),
            Triple("deep water", PrimaryLight, 80 to 155),
        )
    for ((part, color, point) in cases) {
      val (x, y) = point
      assertSameColor(part, color, Color(icon.getPixel(x + MARGIN, y + MARGIN)))
    }
  }

  @Test
  fun splashTheme_showsTheLakeIcon() {
    // The attribute Android itself reads (API 31+), as the splash library passes it on
    val theme =
        context.resources
            .newTheme()
            .apply { applyStyle(R.style.Theme_oncompanion_Starting, true) }
            .obtainStyledAttributes(intArrayOf(android.R.attr.windowSplashScreenAnimatedIcon))

    assertEquals(R.drawable.splash_icon, theme.getResourceId(0, 0))
    theme.recycle()
  }

  @Test
  fun splashIcon_isTheRibbonInTheDawnLake_inBothThemes() {
    // The circle brings its own sky and water, so the dark theme keeps the dawn lake
    for (qualifiers in listOf("+notnight", "+night")) {
      RuntimeEnvironment.setQualifiers(qualifiers)
      assertDawnLake(drawSplashIcon())
    }
  }

  @Test
  fun splashIcon_drawsNothingOutsideTheCircle() {
    val icon = drawSplashIcon()

    for (x in 0 until SIZE) {
      for (y in 0 until SIZE) {
        if (hypot(x + 0.5 - SIZE / 2, y + 0.5 - SIZE / 2) > RADIUS + 1) {
          assertEquals("drawn at ($x, $y)", 0, android.graphics.Color.alpha(icon.getPixel(x, y)))
        }
      }
    }
  }

  private companion object {
    const val SIZE = 288
    const val RADIUS = 80
    const val MARGIN = (SIZE - 2 * RADIUS) / 2
  }
}
