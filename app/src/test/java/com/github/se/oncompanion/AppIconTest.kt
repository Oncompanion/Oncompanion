// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.theme.SurfaceLight
import com.github.se.oncompanion.ui.theme.TertiaryLight
import kotlin.math.hypot
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The home screen icon: the violet ribbon on a cream tile (Figma "Solid · Violet on cream"). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** An adaptive icon layer drawn on its 108 × 108 canvas, one pixel per unit. */
  private fun draw(layer: Drawable): Bitmap {
    val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    layer.setBounds(0, 0, SIZE, SIZE)
    layer.draw(Canvas(bitmap))
    return bitmap
  }

  @Test
  fun bothIcons_areTheVioletRibbonOnACreamTile() {
    for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
      val icon = context.getDrawable(id) as AdaptiveIconDrawable

      assertSameColor("tile", SurfaceLight, Color(draw(icon.background).getPixel(54, 54)))
      // The top of the ribbon's loop, on the tile's vertical axis
      assertSameColor("ribbon", TertiaryLight, Color(draw(icon.foreground).getPixel(54, 33)))
    }
  }

  @Test
  fun ribbon_staysInsideTheSafeZone_soNoMaskCutsIt() {
    val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
    val ribbon = draw(icon.foreground)

    // Every launcher mask keeps the 66-unit circle in the middle of the 108-unit canvas
    for (x in 0 until SIZE) {
      for (y in 0 until SIZE) {
        if (hypot(x + 0.5 - 54, y + 0.5 - 54) > 33) {
          assertTrue(
              "ribbon drawn at ($x, $y)",
              android.graphics.Color.alpha(ribbon.getPixel(x, y)) == 0,
          )
        }
      }
    }
  }

  @Test
  @Config(sdk = [33])
  fun icon_hasAMonochromeLayer_forThemedIcons() {
    val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable

    assertNotNull(icon.monochrome)
  }

  private companion object {
    const val SIZE = 108
  }
}
