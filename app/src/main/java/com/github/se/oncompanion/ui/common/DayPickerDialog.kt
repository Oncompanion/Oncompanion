// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.github.se.oncompanion.R
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test tags of a picker dialog: the picker itself and its two buttons. */
data class PickerTestTags(val picker: String, val confirm: String, val cancel: String)

/**
 * A dialog to pick a calendar day, starting on [date] (Figma: "Log symptoms – Date picker"): white,
 * with the selected day as its headline, and no switch to typing the date. [onConfirm] gets the
 * picked day; dismissing or cancelling calls [onDismiss]. The caller closes the dialog in both
 * cases.
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
  val colors =
      DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
  DatePickerDialog(
      onDismissRequest = onDismiss,
      colors = colors,
      confirmButton = {
        LakeTextButton(
            text = confirmLabel,
            onClick = {
              val millis = pickerState.selectedDateMillis
              if (millis == null) onDismiss() else onConfirm(millis.toPickerDate())
            },
            modifier = Modifier.testTag(testTags.confirm),
        )
      },
      dismissButton = {
        LakeTextButton(
            text = cancelLabel,
            onClick = onDismiss,
            modifier = Modifier.testTag(testTags.cancel),
        )
      },
  ) {
    // Scrolls on a short window (a phone held sideways), where it would otherwise be squeezed and
    // its rows overlap each other and the buttons
    DatePicker(
        state = pickerState,
        colors = colors,
        showModeToggle = false,
        title = {
          Text(
              stringResource(R.string.day_picker_title),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = Spacing.xl, end = Spacing.md, top = Spacing.lg),
          )
        },
        headline = {
          Text(
              (pickerState.selectedDateMillis?.toPickerDate() ?: date).format(headlineFormatter),
              style = MaterialTheme.typography.headlineSmall,
              color = MaterialTheme.colorScheme.onSurface,
              modifier =
                  Modifier.padding(start = Spacing.xl, end = Spacing.md, bottom = Spacing.md),
          )
        },
        modifier = Modifier.verticalScroll(rememberScrollState()).testTag(testTags.picker),
    )
  }
}

// The selected day above the calendar, e.g. "Fri, 30 Oct 2026"
private val headlineFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)

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
