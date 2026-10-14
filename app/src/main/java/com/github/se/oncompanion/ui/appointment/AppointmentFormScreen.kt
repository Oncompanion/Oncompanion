// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Collects the appointment draft and forwards confirmed input to its ViewModel. */
@Composable
fun AppointmentFormScreen(
    viewModel: AppointmentFormViewModel,
    onBack: () -> Unit,
    onSaved: (Instant) -> Unit,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(state.savedAt, state.completionHandled) {
    if (!state.completionHandled)
        state.savedAt?.let {
          viewModel.onCompletionHandled()
          currentOnSaved(it)
        }
  }
  AppointmentFormContent(
      state,
      viewModel::onTitleChange,
      viewModel::onDateChange,
      viewModel::onTimeChange,
      viewModel::onLocationChange,
      viewModel::onTypeChange,
      viewModel::onNotesChange,
      viewModel::onOffsetChange,
      viewModel::save,
      viewModel::clearError,
      onBack,
      modifier,
  )
}

/**
 * Stateless creation form with optional manual location entry until hospital search is released.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentFormContent(
    state: AppointmentFormUiState,
    onTitleChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
    onLocationChange: (String) -> Unit,
    onTypeChange: (AppointmentType) -> Unit,
    onNotesChange: (String) -> Unit,
    onOffsetChange: (ZoneOffset) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var dateOpen by remember { mutableStateOf(false) }
  var timeOpen by remember { mutableStateOf(false) }
  val snackbar = remember { SnackbarHostState() }
  val message =
      state.error?.let {
        stringResource(
            if (it == AppointmentFormError.NOT_SIGNED_IN) R.string.appointment_signed_out
            else R.string.appointment_save_failed
        )
      }
  LaunchedEffect(message) {
    if (message != null) {
      snackbar.showSnackbar(message)
      onErrorShown()
    }
  }
  val compact = isCompactHeight()
  val topBar: @Composable () -> Unit = {
    TopAppBar(
        title = { Text(stringResource(R.string.appointment_add)) },
        navigationIcon = {
          IconButton(onBack, Modifier.testTag(C.Tag.appointment_back)) {
            Icon(
                painterResource(R.drawable.ic_arrow_back),
                stringResource(R.string.appointment_back),
            )
          }
        },
    )
  }
  // Short windows let the header scroll too, leaving room for the focused field.
  Scaffold(
      modifier = modifier.imePadding().testTag(C.Tag.appointment_screen),
      snackbarHost = { SnackbarHost(snackbar) },
      topBar = { if (!compact) topBar() },
  ) { padding ->
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (compact) topBar()
      OutlinedTextField(
          state.title,
          onTitleChange,
          enabled = state.editable,
          label = { Text(stringResource(R.string.appointment_title)) },
          singleLine = true,
          isError =
              (state.titleTouched && state.title.isBlank()) ||
                  state.title.length > Appointment.MAX_TITLE_LENGTH,
          supportingText = {
            Text(
                stringResource(
                    if (state.title.length > Appointment.MAX_TITLE_LENGTH)
                        R.string.appointment_text_limit
                    else if (state.titleTouched && state.title.isBlank())
                        R.string.appointment_title_missing
                    else R.string.appointment_required,
                    Appointment.MAX_TITLE_LENGTH,
                )
            )
          },
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.appointment_title),
      )
      ClickableReadOnlyField(
          state.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
          stringResource(R.string.appointment_date),
          { if (state.editable) dateOpen = true },
          stringResource(R.string.appointment_pick_date),
          trailingIcon = {},
          modifier = Modifier.testTag(C.Tag.appointment_date),
      )
      val use24Hours = DateFormat.is24HourFormat(LocalContext.current)
      ClickableReadOnlyField(
          state.time?.format(DateTimeFormatter.ofPattern(if (use24Hours) "HH:mm" else "h:mm a"))
              ?: "",
          stringResource(R.string.appointment_time),
          { if (state.editable) timeOpen = true },
          stringResource(R.string.appointment_pick_time),
          trailingIcon = {},
          supportingText = { Text(stringResource(R.string.appointment_required)) },
          modifier = Modifier.testTag(C.Tag.appointment_time),
      )
      if (state.time != null && state.offsets.isEmpty())
          Text(
              stringResource(R.string.appointment_missing_time),
              color = MaterialTheme.colorScheme.error,
          )
      if (state.offsets.size > 1) {
        Text(stringResource(R.string.appointment_repeated_time))
        state.offsets.forEach { offset ->
          FilterChip(
              selected = state.chosenOffset == offset,
              enabled = state.editable,
              onClick = { onOffsetChange(offset) },
              label = { Text(stringResource(R.string.appointment_offset, offset.id)) },
          )
        }
      }
      // Temporary manual entry: hospital search is deferred until its service is configured.
      Column {
        OutlinedTextField(
            state.location,
            onLocationChange,
            enabled = state.editable,
            singleLine = true,
            label = { Text(stringResource(R.string.appointment_location)) },
            isError = state.location.length > Appointment.MAX_LOCATION_LENGTH,
            modifier = Modifier.fillMaxWidth().testTag(C.Tag.appointment_location),
        )
        if (state.location.length > Appointment.MAX_LOCATION_LENGTH)
            Text(
                stringResource(R.string.appointment_text_limit, Appointment.MAX_LOCATION_LENGTH),
                color = MaterialTheme.colorScheme.error,
            )
      }
      Text(stringResource(R.string.appointment_type))
      // Separate choices remain readable with large fonts or a narrow window.
      AppointmentType.entries.forEach { type ->
        FilterChip(
            selected = state.type == type,
            enabled = state.editable,
            onClick = { onTypeChange(type) },
            modifier = Modifier.testTag(C.Tag.appointmentType(type.name)),
            label = {
              Text(
                  stringResource(
                      when (type) {
                        AppointmentType.TREATMENT -> R.string.appointment_treatment
                        AppointmentType.CONSULTATION -> R.string.appointment_consultation
                        AppointmentType.OTHER -> R.string.appointment_other
                      }
                  )
              )
            },
        )
      }
      OutlinedTextField(
          state.notes,
          onNotesChange,
          enabled = state.editable,
          minLines = 3,
          label = { Text(stringResource(R.string.appointment_notes)) },
          isError = state.notes.length > Appointment.MAX_NOTES_LENGTH,
          supportingText = {
            if (state.notes.length > Appointment.MAX_NOTES_LENGTH)
                Text(stringResource(R.string.appointment_text_limit, Appointment.MAX_NOTES_LENGTH))
          },
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.appointment_notes),
      )
      SaveButton(
          stringResource(R.string.appointment_save),
          state.canSave,
          state.isSaving,
          onSave,
          C.Tag.appointment_save,
          C.Tag.appointment_saving,
      )
    }
  }
  if (dateOpen)
      DayPickerDialog(
          state.date,
          {
            onDateChange(it)
            dateOpen = false
          },
          { dateOpen = false },
          stringResource(R.string.appointment_confirm),
          stringResource(R.string.appointment_cancel),
          PickerTestTags(
              C.Tag.appointment_picker,
              C.Tag.appointment_confirm,
              C.Tag.appointment_cancel,
          ),
      )
  if (timeOpen) {
    val picker =
        rememberTimePickerState(
            state.time?.hour ?: 12,
            state.time?.minute ?: 0,
            is24Hour = DateFormat.is24HourFormat(LocalContext.current),
        )
    AlertDialog(
        onDismissRequest = { timeOpen = false },
        title = { Text(stringResource(R.string.appointment_pick_time)) },
        text = { TimeInput(picker) },
        confirmButton = {
          TextButton(
              {
                onTimeChange(LocalTime.of(picker.hour, picker.minute))
                timeOpen = false
              },
              Modifier.testTag(C.Tag.appointment_confirm),
          ) {
            Text(stringResource(R.string.appointment_confirm))
          }
        },
        dismissButton = {
          TextButton({ timeOpen = false }, Modifier.testTag(C.Tag.appointment_cancel)) {
            Text(stringResource(R.string.appointment_cancel))
          }
        },
    )
  }
}
