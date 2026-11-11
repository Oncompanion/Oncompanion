// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The sky and the lake of the Paper lake illustrations at one time of day: [top] for the sky,
 * [bottom] for the water. Flat colors, never a gradient between them (DESIGN.md, Paper Lake Rule).
 */
@Immutable data class Horizon(val top: Color, val bottom: Color)

/**
 * The theme's colors that Material 3 has no role for, so screens and illustrations still take every
 * color from the theme, in light and dark.
 *
 * @property primaryText text buttons and links, which DESIGN.md names apart from the primary color
 * @property dawn the illustrations from 05:00 to 11:00
 * @property day the illustrations from 11:00 to 18:00
 * @property dusk the illustrations from 18:00 to 05:00
 * @property onHorizon text drawn on an illustration, at any time of day
 * @property hull the hull of the boats on the lake (members' boats, the illustrations)
 */
@Immutable
data class LakeColors(
    val primaryText: Color,
    val dawn: Horizon,
    val day: Horizon,
    val dusk: Horizon,
    val onHorizon: Color,
    val hull: Color,
)

internal val LightLakeColors =
    LakeColors(
        primaryText = PrimaryTextLight,
        dawn = Horizon(HorizonDawnTopLight, HorizonDawnBottomLight),
        day = Horizon(HorizonDayTopLight, HorizonDayBottomLight),
        dusk = Horizon(HorizonDuskTopLight, HorizonDuskBottomLight),
        onHorizon = OnHorizonLight,
        hull = LakeHullLight,
    )

internal val DarkLakeColors =
    LakeColors(
        primaryText = PrimaryTextDark,
        dawn = Horizon(HorizonDawnTopDark, HorizonDawnBottomDark),
        day = Horizon(HorizonDayTopDark, HorizonDayBottomDark),
        dusk = Horizon(HorizonDuskTopDark, HorizonDuskBottomDark),
        onHorizon = OnHorizonDark,
        hull = LakeHullDark,
    )

// Light by default, so a composable shown without OncompanionTheme (e.g. in a test) still draws.
internal val LocalLakeColors = staticCompositionLocalOf { LightLakeColors }

/** The [LakeColors] of the current theme, light or dark, next to [MaterialTheme.colorScheme]. */
val MaterialTheme.lakeColors: LakeColors
  @Composable @ReadOnlyComposable get() = LocalLakeColors.current
