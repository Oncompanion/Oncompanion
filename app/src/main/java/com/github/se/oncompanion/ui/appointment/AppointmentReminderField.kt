// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.appointment.AppointmentReminder
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.ClickableReadOnlyField
import com.github.se.oncompanion.ui.common.DialogTags
import com.github.se.oncompanion.ui.common.LakeDialog
import com.github.se.oncompanion.ui.common.LakeFilterChip
import com.github.se.oncompanion.ui.theme.Spacing

/** The same wording for a saved reminder in the form, picker and detail. */
@StringRes
internal fun AppointmentReminder.labelResource(): Int =
    when (this) {
      AppointmentReminder.NONE -> R.string.appointment_reminder_none
      AppointmentReminder.FIFTEEN_MINUTES -> R.string.appointment_reminder_fifteen_minutes
      AppointmentReminder.ONE_HOUR -> R.string.appointment_reminder_one_hour
      AppointmentReminder.THREE_HOURS -> R.string.appointment_reminder_three_hours
      AppointmentReminder.ONE_DAY -> R.string.appointment_reminder_one_day
    }

/** Chooses a draft reminder; dismissal leaves the previous choice intact. */
@Composable
internal fun AppointmentReminderField(
    selected: AppointmentReminder,
    enabled: Boolean,
    onSelected: (AppointmentReminder) -> Unit,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  // A save or unavailable appointment must not leave an editable picker on screen.
  LaunchedEffect(enabled) { if (!enabled) open = false }
  ClickableReadOnlyField(
      value = stringResource(selected.labelResource()),
      label = stringResource(R.string.appointment_reminder),
      onClick = { if (enabled) open = true },
      onClickLabel = stringResource(R.string.appointment_reminder_pick),
      trailingIcon = { Icon(painterResource(R.drawable.ic_schedule), null) },
      modifier =
          Modifier.testTag(C.Tag.appointment_reminder).semantics { if (!enabled) disabled() },
  )
  if (open && enabled) {
    LakeDialog(
        title = stringResource(R.string.appointment_reminder),
        confirmLabel = stringResource(R.string.appointment_cancel),
        cancelLabel = null,
        onConfirm = { open = false },
        onCancel = { open = false },
        tags =
            DialogTags(
                dialog = C.Tag.appointment_reminder_dialog,
                confirm = C.Tag.appointment_reminder_cancel,
            ),
    ) {
      // The shared dialog scrolls its content without hiding Cancel in landscape.
      Column(
          Modifier.selectableGroup(),
          verticalArrangement = Arrangement.spacedBy(Spacing.sm),
      ) {
        AppointmentReminder.entries.forEach { choice ->
          LakeFilterChip(
              selected = selected == choice,
              label = stringResource(choice.labelResource()),
              onClick = {
                onSelected(choice)
                open = false
              },
              role = Role.RadioButton,
              modifier = Modifier.fillMaxWidth().testTag(C.Tag.appointmentReminder(choice.name)),
          )
        }
      }
    }
  }
}
