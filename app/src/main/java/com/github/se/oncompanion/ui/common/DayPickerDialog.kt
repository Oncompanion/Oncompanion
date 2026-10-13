// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The test tags of a picker dialog: the picker itself and its two buttons. */
data class PickerTestTags(val picker: String, val confirm: String, val cancel: String)

/**
 * A dialog to pick a calendar day, starting on [date]. [onConfirm] gets the picked day; dismissing
 * or cancelling calls [onDismiss]. The caller closes the dialog in both cases.
 *
 * @param latestDate the last day that can be picked (e.g. today, for something that already
 *   happened), or `null` for no limit
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayPickerDialog(
    date: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String,
    cancelLabel: String,
    testTags: PickerTestTags,
    latestDate: LocalDate? = null,
) {
  val pickerState =
      rememberDatePickerState(
          initialSelectedDateMillis = date.toPickerMillis(),
          selectableDates = latestDate?.let(::selectableUpTo) ?: DatePickerDefaults.AllDates,
      )
  DatePickerDialog(
      onDismissRequest = onDismiss,
      confirmButton = {
        TextButton(
            onClick = {
              val millis = pickerState.selectedDateMillis
              if (millis == null) onDismiss() else onConfirm(millis.toPickerDate())
            },
            modifier = Modifier.testTag(testTags.confirm),
        ) {
          Text(confirmLabel)
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(testTags.cancel)) {
          Text(cancelLabel)
        }
      },
  ) {
    DatePicker(state = pickerState, modifier = Modifier.testTag(testTags.picker))
  }
}

/** Only the days up to [latestDate] (and their years) can be picked. */
@OptIn(ExperimentalMaterial3Api::class)
internal fun selectableUpTo(latestDate: LocalDate): SelectableDates {
  val latestMillis = latestDate.toPickerMillis()
  return object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latestMillis

    override fun isSelectableYear(year: Int) = year <= latestDate.year
  }
}

// The Material date picker counts days in UTC, whatever the phone's time zone
private fun LocalDate.toPickerMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toPickerDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
