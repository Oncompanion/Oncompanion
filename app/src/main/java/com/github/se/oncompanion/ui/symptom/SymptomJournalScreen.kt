package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.severity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Mon 28 Sep", as in the mockup. The app's UI is in English. */
private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

/** "Mon 28 Sep 2025", for days of another year. */
private val dayWithYearFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

/** "14:30". */
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/**
 * The symptom journal (US-8 "Review my symptom journal"): the user's symptoms grouped by day,
 * newest first (Figma: "Symptoms – History").
 */
@Composable
fun SymptomJournalScreen(
    navigationActions: NavigationActions,
    modifier: Modifier = Modifier,
    viewModel: SymptomJournalViewModel = viewModel { SymptomJournalViewModel() },
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // The ViewModel outlives navigation and backgrounding: keep "Today" and "Yesterday" right
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshToday()
    onPauseOrDispose {}
  }
  SymptomJournalContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onRetry = viewModel::load,
      modifier = modifier,
  )
}

/**
 * Stateless content of the journal, so each state can be tested without a ViewModel.
 *
 * @param onRetry reloads the journal after an error
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomJournalContent(
    uiState: SymptomJournalUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.symptoms_screen),
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.symptoms_title)) },
            navigationIcon = { BackButton(onBack) },
        )
      },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_journal_loading)
            )
        uiState.hasError ->
            SymptomMessage(
                title = stringResource(R.string.symptom_journal_error_title),
                hint = stringResource(R.string.symptom_error_hint),
                testTag = C.Tag.symptom_journal_error,
                onRetry = onRetry,
                retryTestTag = C.Tag.symptom_journal_retry,
            )
        uiState.days.isEmpty() ->
            SymptomMessage(
                title = stringResource(R.string.symptom_journal_empty_title),
                hint = stringResource(R.string.symptom_journal_empty_hint),
                testTag = C.Tag.symptom_journal_empty,
            )
        else -> JournalList(uiState.days, uiState.today)
      }
    }
  }
}

@Composable
private fun JournalList(days: List<JournalDay>, today: LocalDate) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(C.Tag.symptom_journal_list),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    days.forEach { day ->
      item(key = "day-${day.date}") { DayHeader(day.date, today) }
      items(day.entries, key = { it.entry.id }) { journalEntry -> EntryRow(journalEntry) }
    }
  }
}

/** "Today", "Yesterday" or the date, above the entries of that day. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
  val text =
      when {
        date == today -> stringResource(R.string.symptom_journal_today)
        date == today.minusDays(1) -> stringResource(R.string.symptom_journal_yesterday)
        date.year == today.year -> date.format(dayFormatter)
        else -> date.format(dayWithYearFormatter)
      }
  Text(
      text = text,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.padding(top = 8.dp)
              .semantics { heading() }
              .testTag(C.Tag.symptomJournalDay(date.toString())),
  )
}

/** One entry: what it was, its severity in words and the time. */
@Composable
private fun EntryRow(journalEntry: JournalEntry) {
  val entry = journalEntry.entry
  Surface(
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptomJournalItem(entry.id)),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    ListItem(
        headlineContent = { Text(entry.title(), style = MaterialTheme.typography.titleMedium) },
        // The severity in words (Figma: "Symptoms – History"), as entered: no colour, no advice
        supportingContent = { Text(stringResource(entry.severity.label)) },
        trailingContent = {
          Text(
              text = journalEntry.time.format(timeFormatter),
              style = MaterialTheme.typography.labelMedium,
          )
        },
    )
  }
}
