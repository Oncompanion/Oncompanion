package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.resources.C
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "29 Sep 2026". The app's UI is in English. */
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * The prescription form (US-24): the user types a prescription and its medications, then saves it.
 *
 * @param onClose called when the user leaves the form without saving
 * @param onSaved called once the prescription is saved
 */
@Composable
fun PrescriptionFormScreen(
    viewModel: PrescriptionFormViewModel,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Leaves the form once the prescription is saved
  val currentOnSaved by rememberUpdatedState(onSaved)
  LaunchedEffect(uiState.isSaved) { if (uiState.isSaved) currentOnSaved() }

  PrescriptionFormContent(
      uiState = uiState,
      onClose = onClose,
      onPrescribedByChange = viewModel::onPrescribedByChange,
      onPrescribedOnChange = viewModel::onPrescribedOnChange,
      onMedicationNameChange = viewModel::onMedicationNameChange,
      onDosageChange = viewModel::onDosageChange,
      onFrequencyChange = viewModel::onFrequencyChange,
      onStartDateChange = viewModel::onStartDateChange,
      onDurationChange = viewModel::onDurationChange,
      onAddMedication = viewModel::addMedication,
      onOpenMedication = viewModel::openMedication,
      onRemoveMedication = viewModel::removeMedication,
      onSave = viewModel::save,
      onErrorShown = viewModel::clearError,
      modifier = modifier,
  )
}

/**
 * Stateless content of the prescription form (Figma "US-24 / Add prescription"), so each state can
 * be tested without a ViewModel.
 *
 * @param onErrorShown called once the error snackbar is gone
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionFormContent(
    uiState: PrescriptionFormUiState,
    onClose: () -> Unit,
    onPrescribedByChange: (String) -> Unit,
    onPrescribedOnChange: (LocalDate) -> Unit,
    onMedicationNameChange: (String) -> Unit,
    onDosageChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onDurationChange: (String) -> Unit,
    onAddMedication: () -> Unit,
    onOpenMedication: (Int) -> Unit,
    onRemoveMedication: (Int) -> Unit,
    onSave: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
  // Shows each saving error once in a snackbar, then tells the view model it was shown
  val snackbarHostState = remember { SnackbarHostState() }
  val errorMessage = uiState.error?.let { stringResource(it.messageRes()) }
  val currentOnErrorShown by rememberUpdatedState(onErrorShown)
  LaunchedEffect(uiState.error) {
    if (errorMessage != null) {
      snackbarHostState.showSnackbar(errorMessage)
      currentOnErrorShown()
    }
  }

  Scaffold(
      modifier = modifier.testTag(C.Tag.prescription_form_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.prescription_form_add_title)) },
            navigationIcon = {
              // Not while saving or once saved: the form then leaves by itself, and a second way
              // out would go back twice
              IconButton(
                  onClick = onClose,
                  enabled = !uiState.isSaving && !uiState.isSaved,
                  modifier = Modifier.testTag(C.Tag.prescription_form_close_button),
              ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.prescription_form_close),
                )
              }
            },
        )
      },
      snackbarHost = { SnackbarHost(snackbarHostState) },
      // Save stays at the bottom of the screen while the fields scroll
      bottomBar = { SaveButton(uiState, onSave) },
  ) { innerPadding ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // What the medications share: the doctor (optional) and the date of the prescription
      OutlinedTextField(
          value = uiState.draft.prescribedBy,
          onValueChange = onPrescribedByChange,
          label = { Text(stringResource(R.string.prescription_form_prescribed_by)) },
          singleLine = true,
          keyboardOptions = textKeyboard(KeyboardCapitalization.Words),
          modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_form_prescribed_by_field),
      )
      DateField(
          label = stringResource(R.string.prescription_form_date),
          date = uiState.draft.prescribedOn,
          onDateChange = onPrescribedOnChange,
          testTag = C.Tag.prescription_form_date_field,
      )

      // The medications in order: the open one with its fields, the others as one row each
      uiState.draft.medications.forEachIndexed { position, medication ->
        if (position == uiState.openMedication) {
          OpenMedication(
              number = position + 1,
              medication = medication,
              canRemove = uiState.canRemoveMedication,
              onNameChange = onMedicationNameChange,
              onDosageChange = onDosageChange,
              onFrequencyChange = onFrequencyChange,
              onStartDateChange = onStartDateChange,
              onDurationChange = onDurationChange,
              onRemove = { onRemoveMedication(position) },
          )
        } else {
          ClosedMedication(
              position = position,
              medication = medication,
              blocksSave = position in uiState.invalidMedications,
              onEdit = { onOpenMedication(position) },
          )
        }
      }

      TextButton(
          onClick = onAddMedication,
          enabled = uiState.canAddMedication,
          modifier = Modifier.testTag(C.Tag.prescription_form_add_medication_button),
      ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.prescription_form_add_medication))
      }
    }
  }
}

/** The fields of the medication being edited, under its title ("Medication 2"). */
@Composable
private fun OpenMedication(
    number: Int,
    medication: MedicationDraft,
    canRemove: Boolean,
    onNameChange: (String) -> Unit,
    onDosageChange: (String) -> Unit,
    onFrequencyChange: (String) -> Unit,
    onStartDateChange: (LocalDate) -> Unit,
    onDurationChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Title, with Remove at the end of the line when there is another medication
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.prescription_form_medication_title, number),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f).testTag(C.Tag.prescription_form_medication_title),
      )
      if (canRemove) {
        TextButton(
            onClick = onRemove,
            modifier = Modifier.testTag(C.Tag.prescription_form_remove_medication_button),
        ) {
          Text(stringResource(R.string.prescription_form_remove_medication))
        }
      }
    }

    // The name is required: "*Required" below it, or a warning once it has been left empty
    var nameEdited by rememberSaveable(number) { mutableStateOf(false) }
    val nameMissing = nameEdited && medication.name.isBlank()
    OutlinedTextField(
        value = medication.name,
        onValueChange = {
          nameEdited = true
          onNameChange(it)
        },
        label = { Text(stringResource(R.string.prescription_form_medication_name)) },
        supportingText = {
          Text(
              stringResource(
                  if (nameMissing) R.string.prescription_form_name_missing
                  else R.string.prescription_form_required
              )
          )
        },
        isError = nameMissing,
        singleLine = true,
        keyboardOptions = textKeyboard(KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_form_medication_name_field),
    )
    OutlinedTextField(
        value = medication.dosage,
        onValueChange = onDosageChange,
        label = { Text(stringResource(R.string.prescription_form_dosage)) },
        singleLine = true,
        keyboardOptions = textKeyboard(KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_form_dosage_field),
    )
    OutlinedTextField(
        value = medication.frequency,
        onValueChange = onFrequencyChange,
        label = { Text(stringResource(R.string.prescription_form_frequency)) },
        singleLine = true,
        keyboardOptions = textKeyboard(KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_form_frequency_field),
    )
    DateField(
        label = stringResource(R.string.prescription_form_start_date),
        date = medication.startDate,
        onDateChange = onStartDateChange,
        testTag = C.Tag.prescription_form_start_date_field,
    )

    // A number of days, empty when the medication has no end; a warning says when it can't be saved
    val durationInvalid = !Medication.isValidDuration(medication.durationDays)
    OutlinedTextField(
        value = medication.durationDays?.toString().orEmpty(),
        onValueChange = onDurationChange,
        label = { Text(stringResource(R.string.prescription_form_duration)) },
        suffix = {
          val days = medication.durationDays ?: 0
          Text(pluralStringResource(R.plurals.prescription_form_duration_unit, days))
        },
        supportingText =
            if (durationInvalid) {
              {
                Text(
                    stringResource(
                        R.string.prescription_form_duration_invalid,
                        Medication.MAX_DURATION_DAYS,
                    )
                )
              }
            } else null,
        isError = durationInvalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(C.Tag.prescription_form_duration_field),
    )
  }
}

/**
 * One row for a medication that isn't open: its name and a summary, or what stops it from being
 * saved. Tapping the row or its pencil opens it for editing.
 */
@Composable
private fun ClosedMedication(
    position: Int,
    medication: MedicationDraft,
    blocksSave: Boolean,
    onEdit: () -> Unit,
) {
  // A medication the user hasn't named yet is shown by its number
  val name =
      medication.name.ifBlank {
        stringResource(R.string.prescription_form_medication_title, position + 1)
      }
  val editLabel = stringResource(R.string.prescription_form_edit_medication, name)
  // Save is disabled while a closed medication can't be saved: say which one and why
  val problem =
      when {
        !blocksSave -> null
        medication.name.isBlank() -> R.string.prescription_form_name_missing
        else -> R.string.prescription_form_duration_check
      }
  val summary = medication.summary()
  ListItem(
      headlineContent = { Text(name) },
      supportingContent =
          when {
            problem != null -> {
              { Text(stringResource(problem), color = MaterialTheme.colorScheme.error) }
            }
            summary.isNotEmpty() -> {
              { Text(summary) }
            }
            else -> null
          },
      trailingContent = {
        IconButton(
            onClick = onEdit,
            modifier = Modifier.testTag(C.Tag.prescriptionFormEditMedication(position)),
        ) {
          Icon(imageVector = Icons.Default.Edit, contentDescription = editLabel)
        }
      },
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      modifier =
          Modifier.testTag(C.Tag.prescriptionFormMedicationRow(position))
              .clickable(onClickLabel = editLabel, onClick = onEdit),
  )
}

/** "1 tablet · Twice a day · 5 days", without the fields left empty. */
@Composable
private fun MedicationDraft.summary(): String {
  val duration = durationDays?.let {
    pluralStringResource(R.plurals.prescription_form_duration_days, it, it)
  }
  return listOfNotNull(dosage.trim(), frequency.trim(), duration)
      .filter { it.isNotEmpty() }
      .joinToString(" · ")
}

/**
 * A field showing [date] that opens a date picker when tapped. [onDateChange] is called when the
 * user confirms a date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    testTag: String,
) {
  var pickerOpen by rememberSaveable { mutableStateOf(false) }
  val pickDateLabel = stringResource(R.string.prescription_form_pick_date)

  OutlinedTextField(
      value = date.format(dateFormatter),
      onValueChange = {},
      readOnly = true,
      label = { Text(label) },
      trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
      singleLine = true,
      modifier =
          Modifier.fillMaxWidth()
              .testTag(testTag)
              // Nothing can be typed here: the keyboard's Next skips it
              .focusProperties { canFocus = false }
              // A text field keeps its taps for itself, so they are caught before it sees them
              .pointerInput(Unit) {
                awaitEachGesture {
                  awaitFirstDown(pass = PointerEventPass.Initial)
                  if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) {
                    pickerOpen = true
                  }
                }
              }
              // Screen readers announce a button that opens the picker, not a text box
              .semantics {
                role = Role.Button
                onClick(label = pickDateLabel) {
                  pickerOpen = true
                  true
                }
              },
  )

  if (pickerOpen) {
    // The Material date picker counts days in UTC, whatever the phone's time zone
    val pickerState =
        rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    DatePickerDialog(
        onDismissRequest = { pickerOpen = false },
        confirmButton = {
          TextButton(
              onClick = {
                pickerState.selectedDateMillis?.let { millis ->
                  onDateChange(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                }
                pickerOpen = false
              },
              modifier = Modifier.testTag(C.Tag.prescription_form_date_picker_confirm),
          ) {
            Text(stringResource(R.string.prescription_form_date_confirm))
          }
        },
        dismissButton = {
          TextButton(
              onClick = { pickerOpen = false },
              modifier = Modifier.testTag(C.Tag.prescription_form_date_picker_cancel),
          ) {
            Text(stringResource(R.string.prescription_form_date_cancel))
          }
        },
    ) {
      DatePicker(
          state = pickerState,
          modifier = Modifier.testTag(C.Tag.prescription_form_date_picker),
      )
    }
  }
}

/** Save, enabled when the form is valid; a spinner replaces its text while saving. */
@Composable
private fun SaveButton(uiState: PrescriptionFormUiState, onSave: () -> Unit) {
  Button(
      onClick = onSave,
      enabled = uiState.canSave,
      modifier =
          Modifier.fillMaxWidth()
              // Keeps the button above the system navigation bar (the app draws edge to edge)
              .navigationBarsPadding()
              .padding(horizontal = 24.dp, vertical = 16.dp)
              .heightIn(min = 56.dp)
              .testTag(C.Tag.prescription_form_save_button),
  ) {
    if (uiState.isSaving) {
      Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.fillMaxSize().testTag(C.Tag.prescription_form_saving),
        )
      }
    } else {
      Text(stringResource(R.string.prescription_form_save))
    }
  }
}

private fun textKeyboard(capitalization: KeyboardCapitalization) =
    KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next)

/** The message shown in the snackbar for each saving error. */
private fun PrescriptionFormError.messageRes(): Int =
    when (this) {
      PrescriptionFormError.NOT_SIGNED_IN -> R.string.prescription_form_error_not_signed_in
      PrescriptionFormError.SAVE_FAILED -> R.string.prescription_form_error_save_failed
    }
