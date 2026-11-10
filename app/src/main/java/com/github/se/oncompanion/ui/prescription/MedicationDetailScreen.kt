// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.prescription

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.DetailWithActions
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.format.DateTimeFormatter
import java.util.Locale

private val medicationDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Collects the live medication detail and exposes navigation through callbacks only. */
@Composable
fun MedicationDetailScreen(
    viewModel: MedicationDetailViewModel,
    onBack: () -> Unit,
    onViewPrescription: (String) -> Unit,
    caregiverOf: String? = null,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  MedicationDetailContent(state, onBack, viewModel::retry, onViewPrescription, caregiverOf)
}

/**
 * Read-only recorded fields using the current appointment-detail layout for M2: the standard
 * toolbar and shared adaptive detail content, without the later Paper lake restyle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationDetailContent(
    state: MedicationDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onViewPrescription: (String) -> Unit,
    caregiverOf: String? = null,
) {
  Scaffold(
      modifier = Modifier.testTag(C.Tag.medication_detail_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.medication_detail_title)) },
            navigationIcon = {
              IconButton(onBack, Modifier.testTag(C.Tag.medication_detail_back)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.prescription_detail_back),
                )
              }
            },
        )
      },
  ) { padding ->
    when (state) {
      is MedicationDetailUiState.Loaded ->
          DetailWithActions(
              showActions = true,
              contentTestTag = C.Tag.medication_detail_content,
              modifier = Modifier.padding(padding),
              actions = {
                LakeButton(
                    text = stringResource(R.string.medication_detail_view_prescription),
                    onClick = { onViewPrescription(state.prescription.id) },
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(
                                start = Spacing.lg,
                                end = Spacing.lg,
                                top = Spacing.lg,
                                bottom = Spacing.xl,
                            )
                            .testTag(C.Tag.medication_detail_prescription),
                )
              },
          ) {
            MedicationFields(state)
          }
      MedicationDetailUiState.Loading ->
          CircularProgressIndicator(
              Modifier.padding(padding).padding(Spacing.lg).testTag(C.Tag.medication_detail_loading)
          )
      MedicationDetailUiState.NotShared ->
          Box(Modifier.padding(padding)) {
            NotSharedMessage(caregiverOf.orEmpty(), CarePermission.PRESCRIPTIONS)
          }
      else ->
          LakeMessageState(
              title =
                  stringResource(
                      if (state == MedicationDetailUiState.Error) R.string.medication_detail_error
                      else R.string.medication_detail_unavailable
                  ),
              message =
                  stringResource(
                      if (state == MedicationDetailUiState.Error)
                          R.string.medication_detail_error_hint
                      else R.string.medication_detail_unavailable_hint
                  ),
              modifier = Modifier.padding(padding).testTag(C.Tag.medication_detail_feedback),
              actionLabel =
                  if (state == MedicationDetailUiState.Error)
                      stringResource(R.string.prescription_detail_retry)
                  else null,
              onAction = onRetry,
              actionTestTag = C.Tag.medication_detail_retry,
          )
    }
  }
}

@Composable
private fun MedicationFields(state: MedicationDetailUiState.Loaded) {
  val medication = state.medication
  val prescription = state.prescription
  Column(
      Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
      verticalArrangement = Arrangement.spacedBy(Spacing.xs),
  ) {
    Text(medication.name, style = MaterialTheme.typography.headlineMedium)
    prescription.addedByName?.let { AddedBy(it) }
    if (!prescription.isConfirmed)
        Text(
            stringResource(R.string.medication_detail_pending),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
  }
  // Keep the same field order as the prescription form, omitting unspecified optional values.
  medication.dosage
      ?.takeIf { it.isNotBlank() }
      ?.let { MedicationField(it, R.string.prescription_form_dosage) }
  medication.frequency
      ?.takeIf { it.isNotBlank() }
      ?.let { MedicationField(it, R.string.prescription_form_frequency) }
  MedicationField(
      medication.startDate.format(medicationDate),
      R.string.prescription_form_start_date,
  )
  medication.durationDays?.let {
    MedicationField(
        pluralStringResource(R.plurals.prescription_detail_duration_days, it, it),
        R.string.prescription_form_duration,
    )
  }
  prescription.prescribedBy
      ?.takeIf { it.isNotBlank() }
      ?.let { MedicationField(it, R.string.prescription_detail_prescribed_by) }
  MedicationField(
      prescription.prescribedOn.format(medicationDate),
      R.string.medication_detail_prescription_date,
  )
}

@Composable
private fun MedicationField(value: String, @StringRes label: Int) {
  ListItem(headlineContent = { Text(value) }, supportingContent = { Text(stringResource(label)) })
}
