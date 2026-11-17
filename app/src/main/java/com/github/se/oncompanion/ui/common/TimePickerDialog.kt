// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.text.format.DateFormat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import java.time.LocalTime

/**
 * A dialog to pick a time of day, starting on [time]: white like [DayPickerDialog], with the same
 * buttons. [onConfirm] gets the picked time; dismissing or cancelling calls [onDismiss]. The caller
 * closes the dialog in both cases.
 *
 * On a short window (a phone held sideways) the time is typed instead of set on a clock, which has
 * a fixed size and would be cut off, and the title leaves room for it. Either way the time is in
 * Display Small, as in DESIGN.md.
 *
 * @param is24Hour whether the time is picked in 24-hour format; by default the phone's setting, as
 *   the appointment and planning screens show times. A screen that always shows `HH:mm` passes
 *   `true`, so the picker matches what it shows.
 * @param testTags the picker and its two buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    time: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String,
    cancelLabel: String,
    testTags: PickerTestTags,
    is24Hour: Boolean = DateFormat.is24HourFormat(LocalContext.current),
) {
  val pickerState =
      rememberTimePickerState(
          initialHour = time.hour,
          initialMinute = time.minute,
          is24Hour = is24Hour,
      )
  val compact = isCompactHeight()
  AlertDialog(
      onDismissRequest = onDismiss,
      containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
      shape = MaterialTheme.shapes.extraLarge,
      title =
          if (compact) null
          else {
            {
              Text(
                  stringResource(R.string.time_picker_title),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
      text = {
        // The time in Display Small (DESIGN.md), where Material draws it in Display Large on the
        // clock and Display Medium when typed
        val typography = MaterialTheme.typography
        MaterialTheme(
            typography =
                typography.copy(
                    displayLarge = typography.displaySmall,
                    displayMedium = typography.displaySmall,
                )
        ) {
          if (compact) {
            TimeInput(state = pickerState, modifier = Modifier.testTag(testTags.picker))
          } else {
            TimePicker(state = pickerState, modifier = Modifier.testTag(testTags.picker))
          }
        }
      },
      confirmButton = {
        LakeButton(
            text = confirmLabel,
            onClick = { onConfirm(LocalTime.of(pickerState.hour, pickerState.minute)) },
            style = LakeButtonStyle.Text,
            modifier = Modifier.testTag(testTags.confirm),
        )
      },
      dismissButton = {
        LakeButton(
            text = cancelLabel,
            onClick = onDismiss,
            style = LakeButtonStyle.Text,
            modifier = Modifier.testTag(testTags.cancel),
        )
      },
  )
}
