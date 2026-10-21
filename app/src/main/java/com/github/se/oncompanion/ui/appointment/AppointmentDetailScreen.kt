// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Connects the appointment observation to its read-only detail content. */
@Composable
fun AppointmentDetailScreen(viewModel: AppointmentDetailViewModel, onBack: () -> Unit) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  AppointmentDetailContent(state, onBack, viewModel::retry)
}

/** Figma entry details adapted to recorded fields, without inferred end times or reminders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentDetailContent(
    state: AppointmentDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
) {
  Scaffold(
      modifier = Modifier.testTag(C.Tag.appointment_detail),
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
    Column(
        Modifier.fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
      when (state) {
        AppointmentDetailUiState.Loading ->
            CircularProgressIndicator(
                Modifier.padding(16.dp).testTag(C.Tag.appointment_detail_loading)
            )
        AppointmentDetailUiState.Unavailable ->
            DetailFeedback(R.string.appointment_unavailable, onRetry)
        AppointmentDetailUiState.Error ->
            DetailFeedback(R.string.appointment_detail_failed, onRetry)
        is AppointmentDetailUiState.Loaded -> AppointmentFields(state.appointment, zoneId)
      }
    }
  }
}

@Composable
private fun DetailFeedback(@StringRes message: Int, onRetry: () -> Unit) {
  Column(Modifier.padding(16.dp).testTag(C.Tag.appointment_detail_feedback)) {
    Text(stringResource(message))
    TextButton(onRetry, Modifier.testTag(C.Tag.appointment_detail_retry)) {
      Text(stringResource(R.string.planning_retry))
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
  // Appointments record a start instant only; display it using the phone's locale and clock format.
  val locale = LocalConfiguration.current.locales[0]
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
