// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R

/** The Empty lake (Figma "Illustration/Empty lake"), above the message of an empty state. */
@Composable
fun EmptyLake(modifier: Modifier = Modifier) {
  Decoration(R.drawable.illustration_empty_lake, modifier.size(width = 72.dp, height = 58.dp))
}

/** Two boats on the lake (Figma "Illustration/Two boats"), when someone joins the care circle. */
@Composable
fun TwoBoats(modifier: Modifier = Modifier) {
  Decoration(R.drawable.illustration_two_boats, modifier.size(width = 240.dp, height = 192.dp))
}

/** The oncompanion ribbon (Figma "Logo/Ribbon"). Unlike the illustrations, TalkBack reads it. */
@Composable
fun RibbonLogo(modifier: Modifier = Modifier, size: Dp = 20.dp) {
  Image(
      painterResource(R.drawable.logo_ribbon),
      contentDescription = stringResource(R.string.app_name),
      modifier = modifier.size(size),
  )
}

/**
 * The colours of a member's boat: a dawn or dusk sail, or a dashed one while they haven't joined.
 */
enum class BoatTone {
  DAWN,
  DUSK,
  PENDING,
}

/** One boat around the user's own in [CircleOnTheLake]. */
data class CircleBoat(val initial: String, val tone: BoatTone)

/**
 * A member of the care circle as a boat with the first letter of [initial] on the sail (Figma
 * "Member boat"), so a first name can be passed as it is. Decorative: the member's name is always
 * written next to it.
 */
@Composable
fun MemberBoat(initial: String, tone: BoatTone, modifier: Modifier = Modifier, height: Dp = 48.dp) {
  val colors = boatColors(tone)
  val measurer = rememberTextMeasurer()
  Canvas(modifier.size(width = height * BOAT_WIDTH / BOAT_HEIGHT, height = height)) {
    val scale = size.height / BOAT_HEIGHT
    drawBoat(colors, scale)
    val text =
        measurer.measure(
            boatInitial(initial),
            TextStyle(
                color = colors.initial,
                fontSize = (INITIAL_SIZE * scale / density / fontScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    drawText(
        text,
        topLeft =
            Offset(
                INITIAL_CENTER.x * scale - text.size.width / 2f,
                INITIAL_CENTER.y * scale - text.size.height / 2f,
            ),
    )
  }
}

/**
 * The care circle on the lake (Figma "Illustration/Circle on the lake"): the user's boat in the
 * middle of the ripples, and the first three [members] around it, left, right, then in front.
 * Decorative: the members are listed by name below it.
 */
@Composable
fun CircleOnTheLake(members: List<CircleBoat>, modifier: Modifier = Modifier) {
  BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(CIRCLE_WIDTH / CIRCLE_HEIGHT)) {
    val unit = maxWidth / CIRCLE_WIDTH
    // The Figma card's 20 dp corners, which the lake's bands would otherwise square off
    Image(
        painterResource(R.drawable.illustration_circle_on_lake),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.matchParentSize().clip(RoundedCornerShape(unit * CIRCLE_CORNER)),
    )
    members.take(BOAT_SLOTS.size).forEachIndexed { index, member ->
      MemberBoat(
          initial = member.initial,
          tone = member.tone,
          height = unit * BOAT_HEIGHT,
          modifier =
              Modifier.offset(x = unit * BOAT_SLOTS[index].x, y = unit * BOAT_SLOTS[index].y),
      )
    }
  }
}

/** The letter on a boat's sail: the first letter of [name], in capitals, or none if it is blank. */
internal fun boatInitial(name: String): String = name.trim().take(1).uppercase()

@Composable
private fun Decoration(drawable: Int, modifier: Modifier) {
  Image(painterResource(drawable), contentDescription = null, modifier = modifier)
}

private data class BoatColors(
    val sail: Color,
    val outline: Color?,
    val hull: Color,
    val initial: Color,
)

@Composable
private fun boatColors(tone: BoatTone): BoatColors {
  val scheme = MaterialTheme.colorScheme
  return when (tone) {
    BoatTone.DAWN ->
        BoatColors(
            scheme.secondaryContainer,
            null,
            colorResource(R.color.lake_hull),
            scheme.onSecondaryContainer,
        )
    BoatTone.DUSK ->
        BoatColors(
            scheme.tertiaryContainer,
            null,
            colorResource(R.color.lake_hull),
            scheme.onTertiaryContainer,
        )
    BoatTone.PENDING ->
        BoatColors(
            scheme.surfaceContainerLowest,
            scheme.outline,
            scheme.outline,
            scheme.onSurfaceVariant,
        )
  }
}

/** The Figma's boat, in its 35 × 37.5 frame scaled by [scale]: the sail, then the hull. */
private fun DrawScope.drawBoat(colors: BoatColors, scale: Float) {
  fun path(vararg points: Pair<Float, Float>) =
      Path().apply {
        points.forEachIndexed { i, (x, y) ->
          if (i == 0) moveTo(x * scale, y * scale) else lineTo(x * scale, y * scale)
        }
        close()
      }
  val sail = path(3.33f to 28.75f, 32.5f to 28.75f, 7.5f to 0f)
  drawPath(sail, colors.sail)
  colors.outline?.let {
    drawPath(
        sail,
        it,
        style =
            Stroke(
                width = scale,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * scale, 2 * scale)),
            ),
    )
  }
  drawPath(path(0f to 30.42f, 35f to 30.42f, 29.17f to 37.5f, 5.83f to 37.5f), colors.hull)
}

// The Figma's boat frame, where its initial sits on the sail, and the initial's size
private const val BOAT_WIDTH = 35f
private const val BOAT_HEIGHT = 37.5f
private val INITIAL_CENTER = Offset(11.2f, 22.5f)
private const val INITIAL_SIZE = 11f

// The circle on the lake's frame, and where its boats sail: left, right, then in front
private const val CIRCLE_WIDTH = 358f
private const val CIRCLE_HEIGHT = 168f
private const val CIRCLE_CORNER = 20f
private val BOAT_SLOTS =
    listOf(Offset(54.5f, 79.67f), Offset(268.5f, 79.67f), Offset(161.5f, 123.67f))
