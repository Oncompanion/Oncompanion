package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Monday 5 October 2026, 14:30". The app's UI is in English. */
private val detailDateFormatter =
    DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH)

/** The detail of one symptom of the journal (US-8). Read-only: editing comes with US-9. */
@Composable
fun SymptomDetailScreen(
    navigationActions: NavigationActions,
    viewModel: SymptomDetailViewModel,
    modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  SymptomDetailContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onRetry = viewModel::load,
      modifier = modifier,
  )
}

/**
 * Stateless content of the detail, so each state can be tested without a ViewModel.
 *
 * @param onRetry reloads the symptom after an error
 * @param zone the time zone the date is shown in
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomDetailContent(
    uiState: SymptomDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.symptom_detail_screen),
      topBar = {
        TopAppBar(
            title = {
              Text(uiState.entry?.title() ?: stringResource(R.string.symptom_detail_title))
            },
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
            SymptomMessage(
                title = stringResource(R.string.symptom_detail_error_title),
                hint = stringResource(R.string.symptom_error_hint),
                testTag = C.Tag.symptom_detail_error,
                onRetry = onRetry,
                retryTestTag = C.Tag.symptom_detail_retry,
            )
        entry == null ->
            SymptomMessage(
                title = stringResource(R.string.symptom_detail_not_found_title),
                hint = stringResource(R.string.symptom_detail_not_found_hint),
                testTag = C.Tag.symptom_detail_not_found,
            )
        else -> EntryDetail(entry, zone)
      }
    }
  }
}

@Composable
private fun EntryDetail(entry: SymptomEntry, zone: ZoneId) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(16.dp)
              .testTag(C.Tag.symptom_detail_content),
      verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Field(stringResource(R.string.symptom_detail_when), C.Tag.symptom_detail_when) {
      Text(
          text = entry.occurredAt.atZone(zone).format(detailDateFormatter),
          style = MaterialTheme.typography.bodyLarge,
      )
    }
    Field(stringResource(R.string.symptom_detail_intensity), C.Tag.symptom_detail_intensity) {
      IntensityBadge(entry.intensity)
    }
    val notes = entry.notes?.takeIf { it.isNotBlank() }
    if (notes != null) {
      Field(stringResource(R.string.symptom_detail_notes), C.Tag.symptom_detail_notes) {
        Text(text = notes, style = MaterialTheme.typography.bodyLarge)
      }
    }
  }
}

/** A field of the detail: a small [label] above its [content]. */
@Composable
private fun Field(label: String, testTag: String, content: @Composable () -> Unit) {
  Column(
      modifier = Modifier.testTag(testTag),
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    content()
  }
}

/** "7/10", read out as "Intensity 7 out of 10". Shown as entered, without any judgement. */
@Composable
private fun IntensityBadge(intensity: Int, modifier: Modifier = Modifier) {
  val description = stringResource(R.string.symptom_intensity_description, intensity)
  Surface(
      modifier = modifier.semantics { contentDescription = description },
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
  ) {
    Text(
        text = stringResource(R.string.symptom_intensity_value, intensity),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}
