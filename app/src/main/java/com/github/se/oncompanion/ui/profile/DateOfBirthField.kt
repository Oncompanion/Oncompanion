// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.DayPickerDialog
import com.github.se.oncompanion.ui.common.LakeTextField
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.TypedDayFormat
import com.github.se.oncompanion.ui.common.rememberDayFormatter
import java.time.LocalDate

/**
 * [date] as the Profile and Edit profile screens show it: a calendar day in the phone's language
 * with a short month, e.g. "12 Mar 1968" on a British phone. A calendar day has no time zone, so it
 * is never converted.
 */
@Composable
internal fun dateOfBirthText(date: LocalDate): String =
    date.format(rememberDayFormatter("dMMMyyyy"))

/**
 * The optional date of birth of Edit profile. It is typed in the phone's format (e.g. DD/MM/YYYY,
 * the separators appear on their own), or picked with the calendar button, which opens the day
 * picker on the date or on [today]. Days after [today] can't be picked; typed, they show an error,
 * as does what can't be read as a day, which shows the format to use.
 *
 * @param date the date of birth, `null` when not set, or while what is typed can't be read
 * @param typed the digits the user typed, or `null` to show [date]
 * @param enabled whether the date can be changed (not while the profile is being saved)
 * @param onDateChange gets the picked day
 * @param onTyped gets the typed digits and the day they read as, `null` while they can't be read
 */
@Composable
internal fun DateOfBirthField(
    date: LocalDate?,
    typed: String?,
    enabled: Boolean,
    today: LocalDate,
    onDateChange: (LocalDate?) -> Unit,
    onTyped: (String, LocalDate?) -> Unit,
) {
  val locale = LocalConfiguration.current.locales[0]
  val format = remember(locale) { TypedDayFormat.of(locale) }
  // What the user typed, else the date (as saved or picked)
  val digits = typed ?: date?.let(format::digits).orEmpty()
  val unreadable = digits.isNotEmpty() && date == null
  var focused by remember { mutableStateOf(false) }
  // After today: typed, or saved by another phone (ahead in time zone, or with a wrong clock)
  val isInFuture = date?.isAfter(today) == true
  // While still typed, the format to follow; the error once all the digits are there, or after
  val stillTyping = unreadable && focused && digits.length < TypedDayFormat.DIGITS
  val showUnreadable = unreadable && !stillTyping
  // Saved so the picker stays open when the phone is turned
  var picking by rememberSaveable { mutableStateOf(false) }

  LakeTextField(
      value = digits,
      onValueChange = {
        val newDigits = format.digitsOf(it)
        onTyped(newDigits, format.parse(newDigits))
      },
      enabled = enabled,
      label = { Text(stringResource(R.string.profile_date_of_birth)) },
      placeholder = { Text(format.placeholder) },
      trailingIcon = {
        IconButton(
            onClick = { picking = true },
            enabled = enabled,
            modifier = Modifier.testTag(C.Tag.edit_profile_date_of_birth_pick),
        ) {
          Icon(
              painterResource(R.drawable.ic_calendar_month),
              contentDescription = stringResource(R.string.edit_profile_date_of_birth_pick),
          )
        }
      },
      supportingText = {
        Text(
            when {
              isInFuture -> stringResource(R.string.edit_profile_date_of_birth_in_future)
              showUnreadable ->
                  stringResource(R.string.edit_profile_date_of_birth_unreadable, format.placeholder)
              stillTyping -> format.placeholder
              else -> stringResource(R.string.edit_profile_optional)
            }
        )
      },
      isError = isInFuture || showUnreadable,
      singleLine = true,
      keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
      visualTransformation = format.visualTransformation,
      modifier =
          Modifier.fillMaxWidth()
              .onFocusChanged { focused = it.isFocused }
              .testTag(C.Tag.edit_profile_date_of_birth),
  )

  if (picking) {
    DayPickerDialog(
        // A future date would open on a day that can't be picked
        date = date?.takeUnless { isInFuture } ?: today,
        onConfirm = {
          onDateChange(it)
          picking = false
        },
        onDismiss = { picking = false },
        confirmLabel = stringResource(R.string.edit_profile_date_of_birth_confirm),
        cancelLabel = stringResource(R.string.edit_profile_cancel),
        testTags =
            PickerTestTags(
                picker = C.Tag.edit_profile_date_of_birth_picker,
                confirm = C.Tag.edit_profile_date_of_birth_confirm,
                cancel = C.Tag.edit_profile_date_of_birth_cancel,
            ),
        latestDate = today,
    )
  }
}
