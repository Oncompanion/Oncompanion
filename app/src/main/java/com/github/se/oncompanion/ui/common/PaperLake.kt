// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
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
 * d'eau, the sun and three bands of water, under a sky in the colour of [time]. At least 210 dp
 * tall and growing with [content]; in landscape or at a large font scale it becomes the 100 dp
 * [PaperLakeBand], as DESIGN.md asks, so the screen keeps room for its content.
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
  Box(
      modifier
          .fillMaxWidth()
          .heightIn(min = HEADER_HEIGHT)
          .background(MaterialTheme.lakeColors.horizonAt(time).top)
  ) {
    // Scaled to cover the width, and kept on the bottom edge so the water always meets the content
    Image(
        painterResource(R.drawable.paper_lake_header),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alignment = Alignment.BottomCenter,
        modifier = Modifier.matchParentSize(),
    )
    content()
  }
}

/**
 * The 100 dp Paper lake band behind a top app bar (Figma "Paper lake band"): the sky in the colour
 * of [time], the Salève at the end and a waterline across the whole width. Decorative, like
 * [PaperLakeHeader].
 *
 * @param time the time whose light the sky shows, e.g. `remember { LocalTime.now() }` on a screen
 */
@Composable
fun PaperLakeBand(
    time: LocalTime,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
  Box(
      modifier
          .fillMaxWidth()
          .heightIn(min = BAND_HEIGHT)
          .background(MaterialTheme.lakeColors.horizonAt(time).top)
  ) {
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
            .background(colorResource(R.color.paper_lake_water))
    )
    content()
  }
}

/**
 * The tall Paper lake of Sign in (Figma "Paper lake scene"): the same lake as [PaperLakeHeader],
 * with a boat, under a sky in the colour of [time]. Decorative.
 *
 * @param time the time whose light the sky shows, e.g. `remember { LocalTime.now() }` on a screen
 */
@Composable
fun PaperLakeScene(
    time: LocalTime,
    modifier: Modifier = Modifier,
) {
  Box(
      modifier
          .fillMaxWidth()
          .aspectRatio(SCENE_RATIO)
          .background(MaterialTheme.lakeColors.horizonAt(time).top)
  ) {
    Image(
        painterResource(R.drawable.paper_lake_scene),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.matchParentSize(),
    )
  }
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
