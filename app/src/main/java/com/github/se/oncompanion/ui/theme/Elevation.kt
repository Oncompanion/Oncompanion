// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

// The ink of the Figma effect style "Oncompanion/Elevation 1", the same in light and dark.
private val ShadowInk = Color(0xFF1F2930)

/**
 * DESIGN.md's only shadow, Elevation 1: a tight shadow under a wide soft one, as in the Figma
 * effect style. The app is flat by default, so screens don't use this list directly but
 * [elevation1].
 */
val Elevation1: List<Shadow> =
    listOf(
        Shadow(radius = 2.dp, color = ShadowInk, offset = DpOffset(0.dp, 1.dp), alpha = 0.06f),
        Shadow(radius = 18.dp, color = ShadowInk, offset = DpOffset(0.dp, 6.dp), alpha = 0.08f),
    )

/**
 * Draws [Elevation1] around [shape], so every raised element shares one shadow. DESIGN.md keeps it
 * for the card that overlaps the header, floating buttons and the voice microphone.
 */
fun Modifier.elevation1(shape: Shape): Modifier =
    Elevation1.fold(this) { modifier, shadow -> modifier.dropShadow(shape, shadow) }
