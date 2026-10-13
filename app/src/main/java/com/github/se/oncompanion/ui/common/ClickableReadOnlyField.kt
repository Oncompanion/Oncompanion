// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * A text field that only shows [value] and calls [onClick] when tapped, e.g. to open a date picker.
 * It looks like the other fields of a form but works as a button, also for screen readers.
 *
 * @param onClickLabel what tapping does, read by screen readers (e.g. "Pick a date")
 */
@Composable
fun ClickableReadOnlyField(
    value: String,
    label: String,
    onClick: () -> Unit,
    onClickLabel: String,
    trailingIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
) {
  val currentOnClick by rememberUpdatedState(onClick)
  OutlinedTextField(
      value = value,
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = trailingIcon,
      supportingText = supportingText,
      isError = isError,
      singleLine = true,
      modifier =
          modifier
              .fillMaxWidth()
              // Nothing can be typed here: the keyboard's Next skips it
              .focusProperties { canFocus = false }
              // A text field keeps its taps for itself, so they are caught before it sees them
              .pointerInput(Unit) {
                awaitEachGesture {
                  awaitFirstDown(pass = PointerEventPass.Initial)
                  if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) {
                    currentOnClick()
                  }
                }
              }
              // Screen readers announce a button, not a text box
              .semantics {
                role = Role.Button
                onClick(label = onClickLabel) {
                  currentOnClick()
                  true
                }
              },
  )
}
