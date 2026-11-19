// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.common.horizonAt
import com.github.se.oncompanion.ui.theme.lakeColors
import java.time.LocalTime
import kotlin.math.max

/** Where the sun of [VoiceLake] is, which is the microphone of the voice screen. */
internal enum class VoiceSun {
  /** Not listening and can't (refused, failed): no sun, an empty sky. */
  HIDDEN,
  /** Listening: its halos open with the voice and breathe while it waits for words. */
  LISTENING,
  /** Listening ended on a pause: one still halo, waiting for Done or Start over. */
  RESTING,
  /** Before Android asks for the microphone, and once Done is tapped: half behind the Salève. */
  SETTLED,
}

// The Figma lake is 390 × 270; the sun's centre and the halos, in its units
private const val LAKE_WIDTH = 390f
private const val LAKE_HEIGHT = 270f
private const val SUN_X = 262f
private const val SUN_Y = 156f
private const val SUN_SIZE = 64f
private const val HALO_SIZE = 132f
private const val MIDDLE_HALO_SIZE = 108f
private const val FIRST_HALO_SIZE = 84f

/** How far below its place the sun is, hidden behind the Salève and the water, or settled. */
private const val HIDDEN_DROP = 90f
private const val SETTLED_DROP = 20f

private const val RISE_MILLIS = 600
private const val SETTLE_MILLIS = 500
private const val BREATH_MILLIS = 2400
private const val BREATH = 0.06f

/**
 * The Paper lake of "Speak your symptoms" (DESIGN.md "Voice"): the sun above the Salève (the moon
 * at dusk) is the microphone, and its halos open with the patient's voice. It covers its space as
 * the other lakes do, with the water on the bottom edge. Decorative: the screen's headline says
 * what is happening. With the system's Remove animations setting on, the halos don't breathe and
 * every change is immediate.
 *
 * @param level how loud the patient is, from 0 to 1, while [sun] is [VoiceSun.LISTENING]
 * @param time the time whose light the sky shows
 */
@Composable
internal fun VoiceLake(
    sun: VoiceSun,
    level: Float,
    modifier: Modifier = Modifier,
    time: LocalTime = remember { LocalTime.now() },
) {
  val horizon = MaterialTheme.lakeColors.horizonAt(time)
  val motion = animationsEnabled()

  // The sun rises from behind the Salève, settles when done, and sets when it can't listen
  val drop by
      animateFloatAsState(
          targetValue =
              when (sun) {
                VoiceSun.HIDDEN -> HIDDEN_DROP
                VoiceSun.SETTLED -> SETTLED_DROP
                VoiceSun.LISTENING,
                VoiceSun.RESTING -> 0f
              },
          animationSpec =
              tween(
                  if (sun == VoiceSun.SETTLED) SETTLE_MILLIS else RISE_MILLIS,
                  easing = LinearOutSlowInEasing,
              ),
          label = "sun",
      )
  // The voice opens the halos like water, never snapping; the outer one follows a little later
  val voice = if (sun == VoiceSun.LISTENING) level else 0f
  val inner by animateFloatAsState(voice, spring(0.6f, Spring.StiffnessLow), label = "inner")
  val outer by animateFloatAsState(voice, spring(0.6f, Spring.StiffnessVeryLow), label = "outer")
  val firstHalo by
      animateFloatAsState(
          if (sun == VoiceSun.LISTENING || sun == VoiceSun.RESTING) 1f else 0f,
          tween(SETTLE_MILLIS),
          label = "firstHalo",
      )
  val breath = if (sun == VoiceSun.LISTENING && motion) breathing() else 1f

  BoxWithConstraints(modifier.background(horizon.top).clearAndSetSemantics {}) {
    // Covers the space like ContentScale.Crop with the water on the bottom edge, as the layers do
    val scale = max(maxWidth / LAKE_WIDTH.dp, maxHeight / LAKE_HEIGHT.dp)
    val left = (maxWidth - LAKE_WIDTH.dp * scale) / 2
    val top = maxHeight - LAKE_HEIGHT.dp * scale
    val density = LocalDensity.current
    fun at(size: Float, extraDrop: () -> Float) =
        Modifier.size((size * scale).dp).offset {
          with(density) {
            IntOffset(
                (left + ((SUN_X - size / 2) * scale).dp).roundToPx(),
                (top + ((SUN_Y + extraDrop() - size / 2) * scale).dp).roundToPx(),
            )
          }
        }

    // Halos, from the widest: flat white paper, more see-through the further out
    Halo(
        at(HALO_SIZE) { drop },
        size = SUN_SIZE + (HALO_SIZE - SUN_SIZE) * outer,
        alpha = 0.22f * outer,
    )
    Halo(
        at(HALO_SIZE) { drop },
        size = SUN_SIZE + (MIDDLE_HALO_SIZE - SUN_SIZE) * inner,
        alpha = 0.32f * inner,
    )
    Halo(
        at(HALO_SIZE) { drop },
        size = (FIRST_HALO_SIZE + 2f * inner) * breath,
        alpha = 0.45f * firstHalo,
    )
    Image(painterResource(R.drawable.voice_sun), null, at(SUN_SIZE) { drop })

    // The Salève and the Jet d'eau hide the sun as it rises, then the water in front
    LakeLayer(R.drawable.voice_lake)
    LakeLayer(R.drawable.voice_lake_water, ColorFilter.tint(horizon.bottom))
    LakeLayer(R.drawable.voice_lake_front)
  }
}

/** One halo of [HALO_SIZE] drawn at [size] (in lake units) and [alpha]. */
@Composable
private fun Halo(modifier: Modifier, size: Float, alpha: Float) {
  Image(
      painterResource(R.drawable.voice_sun_halo),
      null,
      modifier.graphicsLayer {
        scaleX = size / HALO_SIZE
        scaleY = size / HALO_SIZE
        this.alpha = alpha.coerceIn(0f, 1f)
      },
  )
}

/** A layer of the lake, cropped like [VoiceLake] places the sun. */
@Composable
private fun BoxScope.LakeLayer(
    @DrawableRes id: Int,
    colorFilter: ColorFilter? = null,
) {
  Image(
      painterResource(id),
      null,
      Modifier.matchParentSize(),
      Alignment.BottomCenter,
      ContentScale.Crop,
      colorFilter = colorFilter,
  )
}

/** The first halo's slow breath while waiting for words: 1 ± [BREATH]. */
@Composable
private fun breathing(): Float {
  val transition = rememberInfiniteTransition(label = "breath")
  val breath by
      transition.animateFloat(
          initialValue = 1f - BREATH,
          targetValue = 1f + BREATH,
          animationSpec = infiniteRepeatable(tween(BREATH_MILLIS / 2), RepeatMode.Reverse),
          label = "breath",
      )
  return breath
}

/** Whether the system's Remove animations setting is off, so the lake may move. */
@Composable
private fun animationsEnabled(): Boolean {
  val resolver = LocalContext.current.contentResolver
  return remember(resolver) {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
  }
}
