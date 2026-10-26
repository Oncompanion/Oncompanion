// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.medication.MedicationDraft
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.ClickableReadOnlyField
import com.github.se.oncompanion.ui.common.DayPickerDialog
import com.github.se.oncompanion.ui.common.PickerTestTags
import com.github.se.oncompanion.ui.common.SaveButton
import com.github.se.oncompanion.ui.common.isCompactHeight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "29 Sep 2026". The app's UI is in English. */
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * The prescription form (US-24): the user types a prescription and its medications, then saves it.
 * Given a view model that corrects a saved prescription (US-12), it shows that prescription once
 * read, or why it can't.
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
      onRetry = viewModel::load,
      modifier = modifier,
  )
}

/**
 * Stateless content of the prescription form (Figma "US-24 / Add prescription"), so each state can
 * be tested without a ViewModel.
 *
 * @param onErrorShown called once the error snackbar is gone
 * @param onRetry reads the saved prescription again after it couldn't be loaded
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
    onRetry: () -> Unit,
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

  // A short screen (a phone held sideways) keeps nothing fixed: with the keyboard open, a fixed
  // top bar and Save would leave no room for the field being typed
  val scrollsEverything = isCompactHeight()
  // When correcting a saved prescription, its fields only show once it is read
  val isReady = uiState.load == PrescriptionFormLoad.READY

  Scaffold(
      // Ends the screen at the keyboard (the activity is adjustResize): Save sits just above it
      modifier = modifier.testTag(C.Tag.prescription_form_screen).imePadding(),
      // Without fields there is no keyboard, so the top bar can stay fixed
      topBar = { if (!scrollsEverything || !isReady) FormTopBar(uiState, onClose) },
      snackbarHost = { SnackbarHost(snackbarHostState) },
      // Save stays at the bottom of the screen while the fields scroll
      bottomBar = { if (!scrollsEverything && isReady) FormSaveButton(uiState, onSave) },
  ) { innerPadding ->
    if (!isReady) {
      LoadState(uiState.load, onRetry, Modifier.padding(innerPadding))
      return@Scaffold
    }
    Column(
        modifier =
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
    ) {
      // The Scaffold already keeps the content clear of the status bar
      if (scrollsEverything) FormTopBar(uiState, onClose, WindowInsets(0))
      Column(
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
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
        val invalidMedications = uiState.invalidMedications
        uiState.draft.medications.forEachIndexed { position, medication ->
          if (position == uiState.openMedication) {
            OpenMedication(
                number = position + 1,
                medication = medication,
                // A state without a flag for this medication counts its name as touched
                nameTouched = uiState.nameTouched.getOrElse(position) { true },
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
                blocksSave = position in invalidMedications,
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
      if (scrollsEverything) FormSaveButton(uiState, onSave)
    }
  }
}

/** The title and the button that leaves the form without saving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormTopBar(
    uiState: PrescriptionFormUiState,
    onClose: () -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
  TopAppBar(
      title = {
        Text(
            stringResource(
                if (uiState.isEditing) R.string.prescription_form_edit_title
                else R.string.prescription_form_add_title
            )
        )
      },
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
      windowInsets = windowInsets,
  )
}

/**
 * What the form shows instead of its fields while the saved prescription to correct isn't there: a
 * spinner while it is read, or why it can't be corrected.
 */
@Composable
private fun LoadState(load: PrescriptionFormLoad, onRetry: () -> Unit, modifier: Modifier) {
  Column(
      modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    when (load) {
      PrescriptionFormLoad.LOADING ->
          CircularProgressIndicator(Modifier.testTag(C.Tag.prescription_form_loading))
      // No retry: the form keeps listening and fills in if the prescription arrives later
      PrescriptionFormLoad.NOT_FOUND ->
          LoadMessage(
              C.Tag.prescription_form_not_found,
              R.string.prescription_form_not_found_title,
              R.string.prescription_form_not_found_hint,
          )
      PrescriptionFormLoad.NOT_SIGNED_IN ->
          LoadMessage(
              C.Tag.prescription_form_not_signed_in,
              R.string.prescription_form_not_signed_in_title,
              R.string.prescription_form_not_signed_in_hint,
          )
      PrescriptionFormLoad.FAILED -> {
        LoadMessage(
            C.Tag.prescription_form_load_error,
            R.string.prescription_form_load_error_title,
            R.string.prescription_form_load_error_hint,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.testTag(C.Tag.prescription_form_retry_button),
        ) {
          Text(stringResource(R.string.prescription_form_retry))
        }
      }
      // The fields are shown instead
      PrescriptionFormLoad.READY -> Unit
    }
  }
}

/** The title and hint of a [LoadState] message, found by tests through [testTag]. */
@Composable
private fun LoadMessage(testTag: String, @StringRes title: Int, @StringRes hint: Int) {
  Column(
      modifier = Modifier.testTag(testTag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    MessageTexts(title = stringResource(title), hint = stringResource(hint))
  }
}

/** The fields of the medication being edited, under its title ("Medication 2"). */
@Composable
private fun OpenMedication(
    number: Int,
    medication: MedicationDraft,
    nameTouched: Boolean,
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

    // The name is required: "*Required" below it, or a warning once it was edited or left empty
    val nameMissing = nameTouched && medication.name.isBlank()
    OutlinedTextField(
        value = medication.name,
        onValueChange = onNameChange,
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
 * saved. Tapping the row opens it for editing.
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
        !Medication.isValidDuration(medication.durationDays) ->
            R.string.prescription_form_duration_check
        // Anything else the model refuses: don't point at a field that is fine
        else -> R.string.prescription_form_medication_check
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
      // The pencil only shows that the row can be edited: the row is the button
      trailingContent = { Icon(imageVector = Icons.Default.Edit, contentDescription = null) },
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
@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    testTag: String,
) {
  var pickerOpen by rememberSaveable { mutableStateOf(false) }

  ClickableReadOnlyField(
      value = date.format(dateFormatter),
      label = label,
      onClick = { pickerOpen = true },
      onClickLabel = stringResource(R.string.prescription_form_pick_date),
      trailingIcon = { Icon(Icons.Default.DateRange, contentDescription = null) },
      modifier = Modifier.testTag(testTag),
  )

  if (pickerOpen) {
    DayPickerDialog(
        date = date,
        onConfirm = {
          onDateChange(it)
          pickerOpen = false
        },
        onDismiss = { pickerOpen = false },
        confirmLabel = stringResource(R.string.prescription_form_date_confirm),
        cancelLabel = stringResource(R.string.prescription_form_date_cancel),
        testTags =
            PickerTestTags(
                picker = C.Tag.prescription_form_date_picker,
                confirm = C.Tag.prescription_form_date_picker_confirm,
                cancel = C.Tag.prescription_form_date_picker_cancel,
            ),
    )
  }
}

/**
 * Save, enabled when the form is valid; in the bottom bar, or after the fields on a short screen.
 */
@Composable
private fun FormSaveButton(uiState: PrescriptionFormUiState, onSave: () -> Unit) {
  SaveButton(
      text = stringResource(R.string.prescription_form_save),
      enabled = uiState.canSave,
      isSaving = uiState.isSaving,
      onClick = onSave,
      testTag = C.Tag.prescription_form_save_button,
      savingTestTag = C.Tag.prescription_form_saving,
  )
}

private fun textKeyboard(capitalization: KeyboardCapitalization) =
    KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next)

/** The message shown in the snackbar for each saving error. */
private fun PrescriptionFormError.messageRes(): Int =
    when (this) {
      PrescriptionFormError.NOT_SIGNED_IN -> R.string.prescription_form_error_not_signed_in
      PrescriptionFormError.SAVE_FAILED -> R.string.prescription_form_error_save_failed
    }
