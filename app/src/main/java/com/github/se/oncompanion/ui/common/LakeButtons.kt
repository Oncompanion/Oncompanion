// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors

/**
 * The sizes of the Figma "Button" component, shared by the buttons of `ui/common` so they all look
 * the same. Material's buttons are smaller (40 dp) and grey out when disabled.
 */
internal object LakeButtonDefaults {
  /** Every button is 56 dp tall, which is also a comfortable touch target. */
  val MinHeight = 56.dp

  /** 24 dp between the label and each side of the button. */
  val ContentPadding = PaddingValues(horizontal = Spacing.xl)

  /** The border of an outlined button. */
  val OutlineWidth = 1.5.dp

  /** A disabled button keeps its colors and fades as a whole, as the Figma draws it. */
  const val DISABLED_ALPHA = 0.38f
}

/** The Figma height of a button, faded when it isn't [enabled]. */
internal fun Modifier.lakeButton(enabled: Boolean): Modifier =
    heightIn(min = LakeButtonDefaults.MinHeight)
        .alpha(if (enabled) 1f else LakeButtonDefaults.DISABLED_ALPHA)

/**
 * A text button of the Figma (Style=Text), e.g. a dialog's Cancel. [color] is the label's color:
 * Primary Text, or the error color for a destructive action.
 */
@Composable
internal fun LakeTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.lakeColors.primaryText,
) {
  TextButton(
      onClick = onClick,
      enabled = enabled,
      colors = ButtonDefaults.textButtonColors(contentColor = color, disabledContentColor = color),
      contentPadding = LakeButtonDefaults.ContentPadding,
      modifier = modifier.lakeButton(enabled),
  ) {
    Text(text, style = MaterialTheme.typography.buttonLabel)
  }
}

/** The small spinner of the Figma (Progress/Circular, Size=Small), in [color]. */
@Composable
internal fun SmallSpinner(color: Color, modifier: Modifier = Modifier) {
  CircularProgressIndicator(
      color = color,
      strokeWidth = SMALL_SPINNER_STROKE,
      modifier = modifier.size(SMALL_SPINNER_SIZE),
  )
}

// The size and line width of the Figma's small spinner
private val SMALL_SPINNER_SIZE = 24.dp
private val SMALL_SPINNER_STROKE = 2.dp
