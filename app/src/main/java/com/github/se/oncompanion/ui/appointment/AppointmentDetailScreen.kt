// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.DeleteConfirmationDialog
import com.github.se.oncompanion.ui.common.DeleteDialogTags
import com.github.se.oncompanion.ui.common.DetailActionTags
import com.github.se.oncompanion.ui.common.DetailActions
import com.github.se.oncompanion.ui.common.DetailWithActions
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Connects appointment observation and confirmed deletion to the detail content. */
@Composable
fun AppointmentDetailScreen(
    viewModel: AppointmentDetailViewModel,
    onBack: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDeleted: () -> Unit = onBack,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val leave by rememberUpdatedState(onDeleted)
  // Recollect the completed state on resume instead of losing completion while backgrounded.
  LaunchedEffect(viewModel, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      viewModel.uiState.collect {
        if (it.delete.isDeleted && viewModel.canLeaveAfterDeletion()) leave()
      }
    }
  }
  AppointmentDetailContent(
      state,
      onBack,
      viewModel::retry,
      onEdit = onEdit,
      onDelete = viewModel::requestDelete,
      onConfirmDelete = viewModel::confirmDelete,
      onCancelDelete = viewModel::cancelDelete,
  )
}

/** Figma entry details adapted to recorded fields, without inferred end times or reminders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentDetailContent(
    state: AppointmentDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onConfirmDelete: () -> Unit = {},
    onCancelDelete: () -> Unit = {},
) {
  Scaffold(
      modifier = Modifier.testTag(C.Tag.appointment_detail_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.appointment_detail)) },
            navigationIcon = {
              IconButton(onBack, Modifier.testTag(C.Tag.appointment_back)) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.appointment_back),
                )
              }
            },
        )
      },
  ) { padding ->
    when (state) {
      is AppointmentDetailUiState.Loaded ->
          DetailWithActions(
              showActions = state.canDelete && (onEdit != null || onDelete != null),
              contentTestTag = C.Tag.appointment_detail_content,
              modifier = Modifier.padding(padding),
              actions = {
                DetailActions(
                    editLabel = stringResource(R.string.appointment_detail_edit),
                    deleteLabel = stringResource(R.string.appointment_detail_delete),
                    onEdit = onEdit,
                    onDelete = onDelete,
                    tags =
                        DetailActionTags(
                            C.Tag.appointment_detail_edit_button,
                            C.Tag.appointment_detail_delete_button,
                        ),
                )
              },
          ) {
            AppointmentFields(state.appointment, zoneId)
          }
      else ->
          Column(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
              AppointmentDetailUiState.Loading ->
                  CircularProgressIndicator(
                      Modifier.padding(16.dp).testTag(C.Tag.appointment_detail_loading)
                  )
              AppointmentDetailUiState.Unavailable ->
                  DetailFeedback(R.string.appointment_unavailable, onRetry)
              else -> DetailFeedback(R.string.appointment_detail_failed, onRetry)
            }
          }
    }
  }
  if (state.isDeleteDialogShown) {
    DeleteConfirmationDialog(
        title = stringResource(R.string.appointment_delete_title),
        message = stringResource(R.string.appointment_delete_message),
        error = stringResource(R.string.appointment_delete_error),
        confirmLabel = stringResource(R.string.appointment_delete_confirm),
        cancelLabel = stringResource(R.string.appointment_delete_cancel),
        isDeleting = state.delete.isDeleting,
        hasError = state.delete.hasError,
        onConfirm = onConfirmDelete,
        onCancel = onCancelDelete,
        tags =
            DeleteDialogTags(
                C.Tag.appointment_delete_dialog,
                C.Tag.appointment_delete_confirm,
                C.Tag.appointment_delete_cancel,
                C.Tag.appointment_delete_error,
            ),
    )
  }
}

@Composable
private fun DetailFeedback(@StringRes message: Int, onRetry: () -> Unit) {
  Column(Modifier.padding(16.dp).testTag(C.Tag.appointment_detail_feedback)) {
    Text(stringResource(message))
    TextButton(onRetry, Modifier.testTag(C.Tag.appointment_detail_retry)) {
      Text(stringResource(R.string.appointment_detail_retry))
    }
  }
}

@Composable
private fun AppointmentFields(appointment: Appointment, zoneId: ZoneId) {
  val type =
      when (appointment.type) {
        AppointmentType.TREATMENT -> R.string.appointment_treatment
        AppointmentType.CONSULTATION -> R.string.appointment_consultation
        AppointmentType.OTHER -> R.string.appointment_other
      }
  Column(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
        stringResource(type),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
    Text(appointment.title, style = MaterialTheme.typography.headlineMedium)
  }
  // Appointments record a start instant only; display it using the English UI language and the
  // phone's clock format.
  val locale = Locale.ENGLISH
  val pattern =
      if (DateFormat.is24HourFormat(LocalContext.current)) "EEE d MMM yyyy · HH:mm"
      else "EEE d MMM yyyy · h:mm a"
  val dateTime =
      appointment.scheduledAt.atZone(zoneId).format(DateTimeFormatter.ofPattern(pattern, locale))
  ListItem(
      headlineContent = { Text(dateTime) },
      supportingContent = { Text(stringResource(R.string.appointment_date_time)) },
      leadingContent = { Icon(painterResource(R.drawable.ic_schedule), null) },
  )
  appointment.location
      ?.takeIf { it.isNotBlank() }
      ?.let { location ->
        ListItem(
            headlineContent = { Text(location) },
            supportingContent = { Text(stringResource(R.string.appointment_detail_location)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_location), null) },
        )
      }
  appointment.notes
      ?.takeIf { it.isNotBlank() }
      ?.let { notes ->
        ListItem(
            headlineContent = { Text(notes) },
            supportingContent = { Text(stringResource(R.string.appointment_detail_notes)) },
        )
      }
}
