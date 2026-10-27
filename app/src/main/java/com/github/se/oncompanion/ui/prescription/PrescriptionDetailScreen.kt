// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.DeleteConfirmationDialog
import com.github.se.oncompanion.ui.common.DeleteDialogTags
import com.github.se.oncompanion.ui.common.DetailActionTags
import com.github.se.oncompanion.ui.common.DetailActions
import com.github.se.oncompanion.ui.common.DetailWithActions
import java.time.format.DateTimeFormatter
import java.util.Locale

private val actionTags =
    DetailActionTags(
        edit = C.Tag.prescription_detail_edit_button,
        delete = C.Tag.prescription_detail_delete_button,
    )

private val deleteDialogTags =
    DeleteDialogTags(
        dialog = C.Tag.prescription_delete_dialog,
        confirm = C.Tag.prescription_delete_confirm,
        cancel = C.Tag.prescription_delete_cancel,
        error = C.Tag.prescription_delete_error,
    )

/** "29 Sep 2026", as in the list and the form. The app's UI is in English. */
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * The detail of one saved prescription (US-11, Figma: "US-11 / Prescription detail"), opened from
 * the prescriptions list: every saved field, so the patient can check a dosage at any time, also
 * offline.
 *
 * @param onEdit opens the prescription in the form, to correct it (US-12)
 * @param onDeleted called once the prescription is deleted, to leave the screen
 */
@Composable
fun PrescriptionDetailScreen(
    viewModel: PrescriptionDetailViewModel,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Back to the list, which no longer shows the prescription, once it is deleted
  val leave by rememberUpdatedState(onDeleted)
  LaunchedEffect(uiState.delete.isDeleted) { if (uiState.delete.isDeleted) leave() }

  PrescriptionDetailContent(
      uiState = uiState,
      onBack = onBack,
      onRetry = viewModel::load,
      onEdit = onEdit,
      onDelete = viewModel::requestDelete,
      onConfirmDelete = viewModel::confirmDelete,
      onCancelDelete = viewModel::cancelDelete,
      modifier = modifier,
  )
}

/**
 * Stateless content of the detail, so each state can be tested without a ViewModel.
 *
 * @param onRetry reloads the prescription after an error
 * @param onEdit opens the shown prescription in the form
 * @param onDelete asks to confirm the deletion
 * @param onConfirmDelete deletes the prescription, from the confirmation dialog
 * @param onCancelDelete closes the confirmation dialog and keeps the prescription
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrescriptionDetailContent(
    uiState: PrescriptionDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.prescription_detail_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.prescription_detail_title)) },
            navigationIcon = {
              IconButton(
                  onClick = onBack,
                  modifier = Modifier.testTag(C.Tag.prescription_detail_back_button),
              ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.prescription_detail_back),
                )
              }
            },
        )
      },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      val prescription = uiState.prescription
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.prescription_detail_loading)
            )
        uiState.hasError ->
            Message(
                testTag = C.Tag.prescription_detail_error,
                title = stringResource(R.string.prescription_detail_error_title),
                hint = stringResource(R.string.prescription_detail_error_hint),
            ) {
              Button(
                  onClick = onRetry,
                  modifier = Modifier.testTag(C.Tag.prescription_detail_retry_button),
              ) {
                Text(stringResource(R.string.prescription_detail_retry))
              }
            }
        // No retry: the ViewModel keeps listening, so the prescription shows if it comes back
        prescription == null ->
            Message(
                testTag = C.Tag.prescription_detail_not_found,
                title = stringResource(R.string.prescription_detail_not_found_title),
                hint = stringResource(R.string.prescription_detail_not_found_hint),
            )
        // Edit and Delete at the bottom, away from the back arrow, as in the Figma mockup
        else ->
            DetailWithActions(
                showActions = uiState.canEdit,
                contentTestTag = C.Tag.prescription_detail_content,
                actions = {
                  DetailActions(
                      editLabel = stringResource(R.string.prescription_detail_edit),
                      deleteLabel = stringResource(R.string.prescription_detail_delete),
                      onEdit = onEdit,
                      onDelete = onDelete,
                      tags = actionTags,
                  )
                },
            ) {
              PrescriptionDetail(prescription)
            }
      }
    }
  }

  if (uiState.isDeleteDialogShown) {
    DeleteConfirmationDialog(
        title = stringResource(R.string.prescription_delete_title),
        message = stringResource(R.string.prescription_delete_message),
        error = stringResource(R.string.prescription_delete_error),
        confirmLabel = stringResource(R.string.prescription_delete_confirm),
        cancelLabel = stringResource(R.string.prescription_delete_cancel),
        isDeleting = uiState.delete.isDeleting,
        hasError = uiState.delete.hasError,
        onConfirm = onConfirmDelete,
        onCancel = onCancelDelete,
        tags = deleteDialogTags,
    )
  }
}

/** Every field of [prescription], as in the Figma mockup. */
@Composable
private fun PrescriptionDetail(prescription: Prescription) {
  Column(Modifier.padding(vertical = 8.dp)) {
    Text(
        text = prescription.medications.joinToString(", ") { it.name },
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    // The prescriber is optional: no row when the user didn't give one
    val prescribedBy = prescription.prescribedBy?.trim().orEmpty()
    if (prescribedBy.isNotEmpty()) {
      LabelledRow(
          value = prescribedBy,
          label = stringResource(R.string.prescription_detail_prescribed_by),
          icon = rememberVectorPainter(Icons.Default.Person),
          testTag = C.Tag.prescription_detail_prescribed_by,
      )
    }
    LabelledRow(
        value = prescription.prescribedOn.format(dateFormatter),
        label = stringResource(R.string.prescription_detail_date),
        icon = painterResource(R.drawable.ic_schedule),
        testTag = C.Tag.prescription_detail_date,
    )
    Text(
        text = stringResource(R.string.prescription_detail_medications),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { heading() }
                .testTag(C.Tag.prescription_detail_medications_header),
    )
    prescription.medications.forEach { MedicationItem(it) }
  }
}

/** A value with its [label] under it ("Dr. Martin" / "Prescribed by"), after an [icon]. */
@Composable
private fun LabelledRow(value: String, label: String, icon: Painter, testTag: String) {
  ListItem(
      headlineContent = { Text(value) },
      supportingContent = { Text(label) },
      leadingContent = { Icon(icon, contentDescription = null) },
      modifier = Modifier.testTag(testTag),
  )
}

/**
 * One medication: its name, then what was entered of its dosage, frequency and duration ("1 tablet
 * · Twice a day · 5 days"), then its start date. Shown as entered, without any judgment.
 */
@Composable
private fun MedicationItem(medication: Medication) {
  val duration =
      medication.durationDays?.let {
        pluralStringResource(R.plurals.prescription_detail_duration_days, it, it)
      }
  // Only what was entered: a medication with none of the three has no such line
  val details =
      listOfNotNull(medication.dosage, medication.frequency, duration)
          .filter { it.isNotBlank() }
          .joinToString(stringResource(R.string.prescription_detail_separator))
  val starts =
      stringResource(
          R.string.prescription_detail_starts,
          medication.startDate.format(dateFormatter),
      )
  ListItem(
      headlineContent = { Text(medication.name) },
      supportingContent = {
        Column {
          if (details.isNotEmpty()) Text(details)
          Text(starts)
        }
      },
      modifier = Modifier.testTag(C.Tag.prescriptionDetailMedication(medication.id)),
  )
}

/** A centered message instead of the prescription, with an optional [action] under it. */
@Composable
private fun Message(
    testTag: String,
    title: String,
    hint: String,
    action: @Composable () -> Unit = {},
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag(testTag),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    MessageTexts(title = title, hint = hint)
    action()
  }
}
