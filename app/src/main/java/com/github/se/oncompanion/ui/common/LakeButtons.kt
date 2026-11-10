// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.buttonLabel
import com.github.se.oncompanion.ui.theme.lakeColors

/** The styles of the Figma "Button", from the strongest to the quietest. */
enum class LakeButtonStyle {
  /** The one main action of a screen, in teal. */
  Filled,
  /** A strong secondary action, on the teal container. */
  Tonal,
  /** A secondary action with a border, e.g. a detail's Edit. */
  Outlined,
  /** The quietest action, e.g. a dialog's Cancel. */
  Text,
}

/** The sizes shared by every button of the Figma, and by the fields and chips that fade alike. */
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

/**
 * A button of the Figma ("Button" in Brand & Theme), so every screen's buttons look the same.
 * Material's buttons are smaller (40 dp) and grey out when disabled; these are 56 dp pills that
 * fade as a whole.
 *
 * @param isDestructive makes an outlined or text button red while it can be tapped (e.g. a dialog's
 *   Delete); only the destructive action of a screen is red
 * @param isWorking shows a small spinner instead of [text], at full color, and ignores taps, so the
 *   user sees their tap was taken into account (e.g. while saving)
 * @param icon drawn before [text], in the label's color
 * @param workingTestTag the test tag of the spinner shown while [isWorking]
 */
@Composable
fun LakeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LakeButtonStyle = LakeButtonStyle.Filled,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    isWorking: Boolean = false,
    icon: Painter? = null,
    workingTestTag: String = "",
) {
  val colors = MaterialTheme.colorScheme
  val quietLabel =
      if (isDestructive && enabled) colors.error else MaterialTheme.lakeColors.primaryText
  val (container, content) =
      when (style) {
        LakeButtonStyle.Filled -> colors.primary to colors.onPrimary
        LakeButtonStyle.Tonal -> colors.primaryContainer to colors.onPrimaryContainer
        LakeButtonStyle.Outlined,
        LakeButtonStyle.Text -> Color.Transparent to quietLabel
      }
  Button(
      onClick = onClick,
      enabled = enabled && !isWorking,
      // Disabled keeps the same colors: the whole button fades instead
      colors = ButtonDefaults.buttonColors(container, content, container, content),
      border =
          if (style == LakeButtonStyle.Outlined) {
            BorderStroke(LakeButtonDefaults.OutlineWidth, colors.outline)
          } else null,
      contentPadding = LakeButtonDefaults.ContentPadding,
      modifier =
          modifier
              .heightIn(min = LakeButtonDefaults.MinHeight)
              .alpha(if (enabled || isWorking) 1f else LakeButtonDefaults.DISABLED_ALPHA),
  ) {
    if (isWorking) {
      SmallSpinner(LocalContentColor.current, Modifier.testTag(workingTestTag))
    } else {
      if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        Spacer(Modifier.width(Spacing.sm))
      }
      Text(text, style = MaterialTheme.typography.buttonLabel)
    }
  }
}

/**
 * A short text action inside a banner or a snackbar (e.g. Retry), smaller than a button: Label
 * Large in [color], 8 dp on each side, and still a full 48 dp touch target.
 */
@Composable
internal fun LinkAction(
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          modifier
              .heightIn(min = LINK_ACTION_HEIGHT)
              .clip(MaterialTheme.shapes.medium)
              .clickable(role = Role.Button, onClick = onClick)
              .padding(horizontal = Spacing.sm),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
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

// The touch target of a banner's or snackbar's action
private val LINK_ACTION_HEIGHT = 48.dp

// The size of a button's leading icon in the Figma
private val ICON_SIZE = 24.dp

// The size and line width of the Figma's small spinner
private val SMALL_SPINNER_SIZE = 24.dp
private val SMALL_SPINNER_STROKE = 2.dp
