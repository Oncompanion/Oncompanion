// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/**
 * Material's limit for a "compact" window height: under it, a phone is held sideways. Same value as
 * `WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND` in `androidx.window:window-core`.
 */
private val COMPACT_HEIGHT = 480.dp

/**
 * Whether the window is too short to keep a top bar or a button fixed on screen, e.g. a phone held
 * sideways: with the keyboard open, they would leave no room for the field being typed. Screens
 * with text fields then let everything scroll together.
 */
@Composable
fun isCompactHeight(): Boolean =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } <
        COMPACT_HEIGHT
