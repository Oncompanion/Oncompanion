// Co-authored-by: Claude Code <noreply@anthropic.com>

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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.severity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import java.time.LocalDate

/**
 * The symptom journal (US-8 "Review my symptom journal"): the user's symptoms grouped by day,
 * newest first (Figma: "Symptoms – History"). Tapping one opens its detail, and the add button
 * opens the add symptom screen.
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
      onEntryClick = { id -> navigationActions.navigateTo(Screen.symptomDetail(id)) },
      onAddClick = { navigationActions.navigateTo(Screen.SYMPTOM_ADD) },
      onRetry = viewModel::load,
      modifier = modifier,
  )
}

/**
 * Stateless content of the journal, so each state can be tested without a ViewModel.
 *
 * @param onEntryClick opens the entry with the given ID
 * @param onAddClick opens the add symptom screen
 * @param onRetry reloads the journal after an error
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomJournalContent(
    uiState: SymptomJournalUiState,
    onBack: () -> Unit,
    onEntryClick: (String) -> Unit,
    onAddClick: () -> Unit,
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
      floatingActionButton = {
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.symptom_add_title)) },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            onClick = onAddClick,
            modifier = Modifier.testTag(C.Tag.symptom_journal_add),
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
        else -> JournalList(uiState.days, uiState.today, onEntryClick)
      }
    }
  }
}

@Composable
private fun JournalList(days: List<JournalDay>, today: LocalDate, onEntryClick: (String) -> Unit) {
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(C.Tag.symptom_journal_list),
      // The bottom padding keeps the last entry above the add button
      contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    days.forEach { day ->
      item(key = "day-${day.date}") { DayHeader(day.date, today) }
      items(day.entries, key = { it.entry.id }) { journalEntry ->
        EntryRow(journalEntry, onClick = { onEntryClick(journalEntry.entry.id) })
      }
    }
  }
}

/** "Today", "Yesterday" or the date, above the entries of that day. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
  Text(
      text = dayLabel(date, today),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.padding(top = 8.dp)
              .semantics { heading() }
              .testTag(C.Tag.symptomJournalDay(date.toString())),
  )
}

/** One entry: what it was, its severity and the start of its notes (if given) and the time. */
@Composable
private fun EntryRow(journalEntry: JournalEntry, onClick: () -> Unit) {
  val entry = journalEntry.entry
  val summary = entry.summary()
  Surface(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptomJournalItem(entry.id)),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    ListItem(
        headlineContent = { Text(entry.title(), style = MaterialTheme.typography.titleMedium) },
        supportingContent =
            summary?.let { text ->
              { Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        trailingContent = {
          Text(
              text = journalEntry.time.format(symptomTimeFormatter),
              style = MaterialTheme.typography.labelMedium,
          )
        },
    )
  }
}

/**
 * The severity in words (Figma: "Symptoms – History"), as entered: no colour, no advice, then the
 * first line of the notes, so an "Other" entry shows which symptom it was without opening it.
 * `null` when the entry has neither.
 */
@Composable
private fun SymptomEntry.summary(): String? {
  val severity = severity?.let { stringResource(it.label) }
  val notes = notes?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
  return if (severity != null && notes != null) {
    stringResource(R.string.symptom_journal_summary, severity, notes)
  } else {
    severity ?: notes
  }
}
