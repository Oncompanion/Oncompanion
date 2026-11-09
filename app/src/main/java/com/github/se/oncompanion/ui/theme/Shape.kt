// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The corner radii of DESIGN.md, given to `MaterialTheme` so Material components pick them up:
 * - small (8): thumbnails;
 * - medium (12): fields, chips and snackbars;
 * - large (20): cards and lists;
 * - extra large (28): dialogs and sheets.
 *
 * Extra small keeps Material's 4 dp: DESIGN.md has no use for it.
 */
val Shapes =
    Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/**
 * DESIGN.md's Full shape: fully rounded ends, for buttons, the navigation indicator and round icon
 * buttons. Material buttons already use it; this is for a custom pill.
 */
@Suppress("UnusedReceiverParameter") // Read next to the other shapes: MaterialTheme.shapes.full
val Shapes.full: Shape
  get() = CircleShape
