// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.se.oncompanion.R

/**
 * Figtree, bundled in `res/font` (SIL Open Font License, in `assets/licenses`), so the app reads
 * the same on every phone without downloading a font.
 */
val Figtree =
    FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )

// One role of the type scale. Sizes are in sp, so text follows the phone's font size setting.
private fun figtree(size: Int, weight: FontWeight, lineHeight: Int, letterSpacingEm: Double = 0.0) =
    TextStyle(
        fontFamily = Figtree,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = letterSpacingEm.em,
    )

private val Material = Typography()

/**
 * The type scale of DESIGN.md: Figtree for every role, one step larger than Material's defaults for
 * body text, so it stays readable through fatigue. The two largest display roles aren't used in the
 * Figma; they keep Material's sizes in Figtree.
 */
val Typography =
    Typography(
        displayLarge = Material.displayLarge.copy(fontFamily = Figtree),
        displayMedium = Material.displayMedium.copy(fontFamily = Figtree),
        displaySmall = figtree(36, FontWeight.Bold, 44, -0.042),
        headlineLarge = figtree(32, FontWeight.Bold, 40, -0.0375),
        headlineMedium = figtree(28, FontWeight.Bold, 36, -0.036),
        headlineSmall = figtree(24, FontWeight.SemiBold, 32, -0.021),
        titleLarge = figtree(22, FontWeight.SemiBold, 28, -0.014),
        titleMedium = figtree(17, FontWeight.SemiBold, 24),
        titleSmall = figtree(15, FontWeight.SemiBold, 20),
        bodyLarge = figtree(17, FontWeight.Normal, 26),
        bodyMedium = figtree(15, FontWeight.Normal, 22),
        bodySmall = figtree(13, FontWeight.Normal, 18),
        labelLarge = figtree(15, FontWeight.SemiBold, 20),
        labelMedium = figtree(13, FontWeight.SemiBold, 16, 0.015),
        labelSmall = figtree(12, FontWeight.Medium, 16, 0.025),
    )

/**
 * The label of every button (Figma "Button": Figtree SemiBold 17/20), a step larger than Label
 * Large so the main actions read easily through fatigue. Read as `MaterialTheme.typography`.
 */
val Typography.buttonLabel: TextStyle
  get() = labelLarge.copy(fontSize = 17.sp, lineHeight = 20.sp)
