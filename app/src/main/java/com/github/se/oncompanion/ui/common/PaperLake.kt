// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.Horizon
import com.github.se.oncompanion.ui.theme.LakeColors
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalTime

/** The three lights of the Paper lake: its sky follows the time of day (DESIGN.md, Horizon). */
enum class TimeOfDay {
  DAWN,
  DAY,
  DUSK,
}

/** Dawn from 05:00 to 11:00, day from 11:00 to 18:00, dusk from 18:00 to 05:00. */
fun timeOfDayAt(time: LocalTime): TimeOfDay =
    when {
      time < DAWN_START -> TimeOfDay.DUSK
      time < DAY_START -> TimeOfDay.DAWN
      time < DUSK_START -> TimeOfDay.DAY
      else -> TimeOfDay.DUSK
    }

/**
 * The Paper lake behind the title of a main screen (Figma "Paper lake header"): the Salève, the Jet
 * d'eau, the sun and three bands of water, with the sky and the far water in the colours of [time].
 * At least 210 dp tall and growing with [content]; in landscape or at a large font scale it becomes
 * the 100 dp [PaperLakeBand], as DESIGN.md asks, so the screen keeps room for its content.
 *
 * The illustration is decorative: TalkBack only reads [content].
 *
 * @param time the time whose light the sky shows, e.g. `remember { LocalTime.now() }` on a screen
 */
@Composable
fun PaperLakeHeader(
    time: LocalTime,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
  if (isCompactHeight() || LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
    PaperLakeBand(time, modifier, content)
    return
  }
  val horizon = MaterialTheme.lakeColors.horizonAt(time)
  Box(modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT).background(horizon.top)) {
    // Scaled to cover the width, and kept on the bottom edge so the water always meets the content
    LakeLayers(
        R.drawable.paper_lake_header,
        R.drawable.paper_lake_header_water,
        R.drawable.paper_lake_header_front,
        horizon.bottom,
        ContentScale.Crop,
        Alignment.BottomCenter,
    )
    content()
  }
}

/**
 * The 100 dp Paper lake band behind a top app bar (Figma "Paper lake band"): the sky in the colour
 * of [time], the Salève at the end and a waterline in its lake colour across the whole width.
 * Decorative, like [PaperLakeHeader].
 *
 * @param time the time whose light the sky shows, e.g. `remember { LocalTime.now() }` on a screen
 */
@Composable
fun PaperLakeBand(
    time: LocalTime,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
  val horizon = MaterialTheme.lakeColors.horizonAt(time)
  Box(modifier.fillMaxWidth().heightIn(min = BAND_HEIGHT).background(horizon.top)) {
    // Drawn at its own height and kept at the end, as in the Figma, whatever the width
    Image(
        painterResource(R.drawable.paper_lake_band),
        contentDescription = null,
        contentScale = ContentScale.FillHeight,
        alignment = Alignment.BottomEnd,
        modifier = Modifier.matchParentSize(),
    )
    Box(
        Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(WATERLINE_HEIGHT)
            .background(horizon.bottom)
    )
    content()
  }
}

/**
 * The tall Paper lake of Sign in (Figma "Paper lake scene"): the same lake as [PaperLakeHeader],
 * with a boat, its sky and far water in the colours of [time]. Decorative.
 *
 * @param time the time whose light the sky shows, e.g. `remember { LocalTime.now() }` on a screen
 */
@Composable
fun PaperLakeScene(
    time: LocalTime,
    modifier: Modifier = Modifier,
) {
  val horizon = MaterialTheme.lakeColors.horizonAt(time)
  Box(modifier.fillMaxWidth().aspectRatio(SCENE_RATIO).background(horizon.top)) {
    LakeLayers(
        R.drawable.paper_lake_scene,
        R.drawable.paper_lake_scene_water,
        R.drawable.paper_lake_scene_front,
        horizon.bottom,
        ContentScale.FillBounds,
        Alignment.Center,
    )
  }
}

/**
 * A Paper lake drawn in three layers with the same scaling: the shapes behind the far water, the
 * far water in [lake] (the time's lake colour, DESIGN.md's Horizon), then the nearer water, the
 * light and any boat in front. The drawables are split so only the far water changes colour.
 */
@Composable
private fun BoxScope.LakeLayers(
    @DrawableRes behind: Int,
    @DrawableRes water: Int,
    @DrawableRes front: Int,
    lake: Color,
    scale: ContentScale,
    alignment: Alignment,
) {
  val layer = Modifier.matchParentSize()
  Image(painterResource(behind), null, layer, alignment, scale)
  Image(painterResource(water), null, layer, alignment, scale, colorFilter = ColorFilter.tint(lake))
  Image(painterResource(front), null, layer, alignment, scale)
}

/** The theme's sky and lake colours at [time]. */
private fun LakeColors.horizonAt(time: LocalTime): Horizon =
    when (timeOfDayAt(time)) {
      TimeOfDay.DAWN -> dawn
      TimeOfDay.DAY -> day
      TimeOfDay.DUSK -> dusk
    }

private val DAWN_START = LocalTime.of(5, 0)
private val DAY_START = LocalTime.of(11, 0)
private val DUSK_START = LocalTime.of(18, 0)

// The Figma's heights of the header and the band, and of the band's waterline
private val HEADER_HEIGHT = 210.dp
private val BAND_HEIGHT = 100.dp
private val WATERLINE_HEIGHT = 4.dp

// From this font scale on, the header shrinks to the band (DESIGN.md: "a large font scale")
private const val LARGE_FONT_SCALE = 1.3f

// The Sign in scene's frame
private const val SCENE_RATIO = 390f / 470f
