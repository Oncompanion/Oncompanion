// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.severity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.DeleteConfirmationDialog
import com.github.se.oncompanion.ui.common.DeleteDialogTags
import com.github.se.oncompanion.ui.common.DetailActionTags
import com.github.se.oncompanion.ui.common.DetailActions
import com.github.se.oncompanion.ui.common.DetailWithActions
import com.github.se.oncompanion.ui.common.LakeFilterChip
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.Spacing
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val actionTags =
    DetailActionTags(edit = C.Tag.symptom_detail_edit, delete = C.Tag.symptom_detail_delete)

private val deleteDialogTags =
    DeleteDialogTags(
        dialog = C.Tag.symptom_delete_dialog,
        confirm = C.Tag.symptom_delete_confirm,
        cancel = C.Tag.symptom_delete_cancel,
        error = C.Tag.symptom_delete_error,
    )

/** "Monday 5 October 2026, 14:30". The app's UI is in English. */
private val detailDateFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

/**
 * The detail of one symptom of the journal (US-8), from which the user can edit or delete it
 * (US-9). Goes back to the journal once it is deleted.
 */
@Composable
fun SymptomDetailScreen(
    navigationActions: NavigationActions,
    viewModel: SymptomDetailViewModel,
    modifier: Modifier = Modifier,
    caregiverOf: String? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  // Back to the journal, which no longer shows the symptom, once it is deleted
  val goBack by rememberUpdatedState(navigationActions::goBack)
  LaunchedEffect(uiState.delete.isDeleted) { if (uiState.delete.isDeleted) goBack() }

  SymptomDetailContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onRetry = viewModel::load,
      onEdit = { uiState.entry?.let { navigationActions.navigateTo(Screen.symptomEdit(it.id)) } },
      onDelete = viewModel::requestDelete,
      onConfirmDelete = viewModel::confirmDelete,
      onCancelDelete = viewModel::cancelDelete,
      modifier = modifier,
      caregiverOf = caregiverOf,
  )
}

/**
 * Stateless content of the detail, so each state can be tested without a ViewModel.
 *
 * @param onRetry reloads the symptom after an error
 * @param onEdit opens the symptom in the edit form
 * @param onDelete asks to confirm the deletion
 * @param onConfirmDelete deletes the symptom, from the confirmation dialog
 * @param onCancelDelete closes the confirmation dialog and keeps the symptom
 * @param zone the time zone the date is shown in
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun SymptomDetailContent(
    uiState: SymptomDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    onEdit: () -> Unit = {},
    caregiverOf: String? = null,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.symptom_detail_screen),
      topBar = {
        LakeTopAppBar(
            title = uiState.entry?.title() ?: stringResource(R.string.symptom_detail_title),
            navigationIcon = { BackButton(onBack) },
        )
      },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      val entry = uiState.entry
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_detail_loading)
            )
        uiState.hasError ->
            LakeMessageState(
                title = stringResource(R.string.symptom_detail_error_title),
                message = stringResource(R.string.symptom_error_hint),
                actionLabel = stringResource(R.string.symptom_retry),
                onAction = onRetry,
                actionTestTag = C.Tag.symptom_detail_retry,
                modifier = Modifier.align(Alignment.Center).testTag(C.Tag.symptom_detail_error),
            )
        // The patient a caregiver follows doesn't share their symptoms (anymore)
        uiState.isNotShared && caregiverOf != null ->
            NotSharedMessage(caregiverOf, CarePermission.SYMPTOMS, Modifier.align(Alignment.Center))
        entry == null ->
            LakeMessageState(
                title = stringResource(R.string.symptom_detail_not_found_title),
                message = stringResource(R.string.symptom_detail_not_found_hint),
                modifier = Modifier.align(Alignment.Center).testTag(C.Tag.symptom_detail_not_found),
            )
        // Edit and Delete at the bottom, away from the back button, as in the Figma mockup
        else ->
            DetailWithActions(
                showActions = uiState.canDelete,
                contentTestTag = C.Tag.symptom_detail_content,
                actions = {
                  DetailActions(
                      editLabel = stringResource(R.string.symptom_detail_edit),
                      deleteLabel = stringResource(R.string.symptom_detail_delete),
                      onEdit = onEdit,
                      onDelete = onDelete,
                      tags = actionTags,
                  )
                },
            ) {
              EntryDetail(entry, zone, uiState.addedByName)
            }
      }
    }
  }

  if (uiState.isDeleteDialogShown) {
    DeleteConfirmationDialog(
        title = stringResource(R.string.symptom_delete_title),
        message = stringResource(R.string.symptom_delete_message),
        error = stringResource(R.string.symptom_delete_error),
        confirmLabel = stringResource(R.string.symptom_delete_confirm),
        cancelLabel = stringResource(R.string.symptom_delete_cancel),
        isDeleting = uiState.delete.isDeleting,
        hasError = uiState.delete.hasError,
        onConfirm = onConfirmDelete,
        onCancel = onCancelDelete,
        tags = deleteDialogTags,
    )
  }
}

/**
 * The symptom: what was felt, then when and how strong in one card, the notes and who logged it.
 * The symptoms are listed in full, since the title shows only the first line.
 */
@Composable
private fun EntryDetail(entry: SymptomEntry, zone: ZoneId, addedByName: String?) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(start = Spacing.lg, top = Spacing.md, end = Spacing.lg, bottom = Spacing.lg),
      verticalArrangement = Arrangement.spacedBy(Spacing.lg),
  ) {
    SymptomsSection(entry)
    InfoCard(entry, zone)
    val notes = entry.notes?.takeIf { it.isNotBlank() }
    if (notes != null) {
      Column(
          modifier = Modifier.testTag(C.Tag.symptom_detail_notes),
          verticalArrangement = Arrangement.spacedBy(Spacing.xs),
      ) {
        Text(
            text = stringResource(R.string.symptom_detail_notes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = notes,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    // A symptom a caregiver logged for the patient says who did
    addedByName?.let { AddedBy(it) }
  }
}

/**
 * Every symptom of the entry, as dawn chips like the ones picked on the log form, right under their
 * heading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymptomsSection(entry: SymptomEntry) {
  Column(
      modifier = Modifier.testTag(C.Tag.symptom_detail_symptoms),
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    Text(
        text = stringResource(R.string.symptom_detail_symptoms),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    // Read-only chips: they show what was logged, nothing to toggle here
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
      entry.types.forEach { type ->
        LakeFilterChip(selected = true, onClick = null, label = stringResource(type.label))
      }
    }
  }
}

/** When it was felt and, if given, how strong: rows of one white card, split by a divider. */
@Composable
private fun InfoCard(entry: SymptomEntry, zone: ZoneId) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
      InfoRow(
          value = entry.occurredAt.atZone(zone).format(detailDateFormatter),
          label = stringResource(R.string.symptom_detail_when),
          testTag = C.Tag.symptom_detail_when,
      ) {
        // Decorative: the label says what the row is
        Icon(painterResource(R.drawable.ic_schedule), contentDescription = null)
      }
      // The intensity is optional: no row when the user didn't give one
      val intensity = entry.intensity
      val severity = entry.severity
      if (intensity != null && severity != null) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        InfoRow(
            value = stringResource(severity.label),
            label = stringResource(R.string.symptom_detail_intensity),
            testTag = C.Tag.symptom_detail_intensity,
        ) {
          IntensityBadge(intensity)
        }
      }
    }
  }
}

/** A row of the card: [leading], then its [value] above its [label]. */
@Composable
private fun InfoRow(
    value: String,
    label: String,
    testTag: String,
    leading: @Composable () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .padding(horizontal = Spacing.lg, vertical = Spacing.md)
              .testTag(testTag),
      horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    leading()
    Column {
      Text(text = value, style = MaterialTheme.typography.titleMedium)
      Text(
          text = label,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * "7/10", read out as "Intensity 7 out of 10". Shown as entered, on the same container whatever the
 * value, so its colour never judges it.
 */
@Composable
private fun IntensityBadge(intensity: Int, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.symptom_intensity_description, intensity)
  Surface(
      modifier =
          modifier.heightIn(min = BADGE_HEIGHT).semantics { contentDescription = description },
      shape = MaterialTheme.shapes.small,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
          text = stringResource(R.string.symptom_intensity_value, intensity),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(horizontal = Spacing.sm),
      )
    }
  }
}

// The intensity badge of the Figma's symptom detail
private val BADGE_HEIGHT = 36.dp
