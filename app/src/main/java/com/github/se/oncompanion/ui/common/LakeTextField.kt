// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import kotlin.math.roundToInt

/**
 * The Figma "Text field": the label inside the box, above the value once there is one, unlike
 * Material's outlined field that puts it on the border. Takes the same parameters as
 * `OutlinedTextField`, so a screen only swaps the call.
 *
 * Its box shows its state: a 1 dp outline, a 1.5 dp red border [isError], a dawn box with a 1.5 dp
 * secondary border for a value the app drafted that the user should check ([isCheck]), and a 2 dp
 * border while the user types. [supportingText] goes below the box, in the state's color.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LakeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    suffix: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    isCheck: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
  val interactions = remember { MutableInteractionSource() }
  val focused by interactions.collectIsFocusedAsState()
  val box = boxStyle(isError = isError, isCheck = isCheck, focused = focused && !readOnly)
  val colors = fieldColors(box)
  val errorMessage = stringResource(R.string.field_error)
  BasicTextField(
      value = value,
      onValueChange = onValueChange,
      modifier =
          modifier
              // Screen readers announce the error, as on Material's fields
              .semantics { if (isError) error(errorMessage) }
              // As wide as Material's field when a screen gives it no width
              .defaultMinSize(minWidth = TextFieldDefaults.MinWidth)
              .alpha(if (enabled) 1f else LakeButtonDefaults.DISABLED_ALPHA),
      enabled = enabled,
      readOnly = readOnly,
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = box.content),
      cursorBrush = SolidColor(box.border),
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      interactionSource = interactions,
      singleLine = singleLine,
      maxLines = maxLines,
      minLines = minLines,
  ) { innerTextField ->
    TextFieldDefaults.DecorationBox(
        value = value,
        innerTextField = innerTextField,
        enabled = enabled,
        singleLine = singleLine,
        visualTransformation = VisualTransformation.None,
        interactionSource = interactions,
        isError = isError,
        label = label?.let { { NoTallerThanSmall(it) } },
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        suffix = suffix,
        supportingText = supportingText,
        colors = colors,
        // Only the box gets the border and the fill: the supporting text stays below it
        container = {
          val shape = MaterialTheme.shapes.medium
          Box(Modifier.background(box.fill, shape).border(box.borderWidth, box.border, shape))
        },
    )
  }
}

/** How the box of a [LakeTextField] looks in one state (Figma: State=Empty, Error, Check). */
private class BoxStyle(
    val fill: Color,
    val border: Color,
    val borderWidth: Dp,
    val label: Color,
    val content: Color,
    val supporting: Color,
)

@Composable
private fun boxStyle(isError: Boolean, isCheck: Boolean, focused: Boolean): BoxStyle {
  val colors = MaterialTheme.colorScheme
  // Typing thickens the border; a field in error or to check keeps its color while typed in
  val width = if (focused) 2.dp else if (isError || isCheck) 1.5.dp else 1.dp
  return when {
    isError ->
        BoxStyle(
            Color.Transparent,
            colors.error,
            width,
            colors.error,
            colors.onSurface,
            colors.error,
        )
    isCheck ->
        BoxStyle(
            colors.secondaryContainer,
            colors.secondary,
            width,
            colors.onSecondaryContainer,
            colors.onSecondaryContainer,
            colors.secondary,
        )
    else -> {
      val accent = if (focused) colors.primary else colors.outline
      val label = if (focused) colors.primary else colors.onSurfaceVariant
      BoxStyle(Color.Transparent, accent, width, label, colors.onSurface, colors.onSurfaceVariant)
    }
  }
}

/** Material's colors for the label, icons and supporting text, from the box's [style]. */
@Composable
private fun fieldColors(style: BoxStyle) =
    TextFieldDefaults.colors(
        focusedLabelColor = style.label,
        unfocusedLabelColor = style.label,
        disabledLabelColor = style.label,
        errorLabelColor = style.label,
        focusedSupportingTextColor = style.supporting,
        unfocusedSupportingTextColor = style.supporting,
        disabledSupportingTextColor = style.supporting,
        errorSupportingTextColor = style.supporting,
        // The Figma's trailing icons are in ink, whatever the state
        focusedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
        disabledTrailingIconColor = MaterialTheme.colorScheme.onSurface,
        errorTrailingIconColor = MaterialTheme.colorScheme.onSurface,
        // The whole field fades when disabled, so its parts keep their enabled colors
        disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledPrefixColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledSuffixColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

/**
 * The [label], counted as no taller than the room Material keeps for it once it is small above the
 * value. Inside an empty field the label is as large as the value, and Material would make the box
 * taller for it (60 dp instead of the Figma's 58). This way the box is as tall empty as filled, at
 * any text size, so focusing a field never grows it out of a scrolled view. A label that wraps
 * (e.g. at a large text size) keeps its full height, so the box grows for it instead of the label
 * covering the value.
 */
@Composable
private fun NoTallerThanSmall(label: @Composable () -> Unit) {
  val density = LocalDensity.current
  // Material gives the label a style between bodyLarge and bodySmall while it shrinks
  val line = LocalTextStyle.current.lineHeightPx(density)
  val smallLabel = with(density) { MaterialTheme.typography.bodySmall.lineHeight.roundToPx() }
  Box(
      Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val wraps = (placeable.height / line).roundToInt() > 1
        val height = if (wraps) placeable.height else minOf(placeable.height, smallLabel)
        // Centered on the space it is counted for, so it is drawn where Material places it
        layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
      }
  ) {
    label()
  }
}

/**
 * The height of one line of this style, as Android lays it out: with a large text size the font
 * grows less than 2x, and the line height keeps its ratio to the font size.
 */
private fun TextStyle.lineHeightPx(density: Density): Float =
    with(density) { fontSize.toPx() } * lineHeight.value / fontSize.value
