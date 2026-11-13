// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.severity
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.EmptyLake
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonDefaults
import com.github.se.oncompanion.ui.common.LakeMessageState
import com.github.se.oncompanion.ui.common.LakeTopAppBar
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.theme.Spacing
import com.github.se.oncompanion.ui.theme.elevation1
import com.github.se.oncompanion.ui.theme.full
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
    caregiverOf: String? = null,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Keeps "Today" and "Yesterday" right while the journal is on screen: refreshes when it comes
  // back, then at each midnight
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
  }
  SymptomJournalContent(
      uiState = uiState,
      onBack = navigationActions::goBack,
      onEntryClick = { id -> navigationActions.navigateTo(Screen.symptomDetail(id)) },
      onAddClick = { navigationActions.navigateTo(Screen.SYMPTOM_ADD) },
      onRetry = viewModel::load,
      modifier = modifier,
      caregiverOf = caregiverOf,
  )
}

/**
 * Stateless content of the journal, so each state can be tested without a ViewModel.
 *
 * @param onEntryClick opens the entry with the given ID
 * @param onAddClick opens the add symptom screen
 * @param onRetry reloads the journal after an error
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun SymptomJournalContent(
    uiState: SymptomJournalUiState,
    onBack: () -> Unit,
    onEntryClick: (String) -> Unit,
    onAddClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    caregiverOf: String? = null,
) {
  Scaffold(
      modifier = modifier.testTag(C.Tag.symptoms_screen),
      topBar = {
        LakeTopAppBar(
            title = stringResource(R.string.symptoms_title),
            navigationIcon = { BackButton(onBack) },
        )
      },
      floatingActionButton = {
        // The patient, or a caregiver they let add symptoms
        if (uiState.canAdd) {
          // The screen's one filled button, floating with the app's only shadow
          LakeButton(
              text = stringResource(R.string.symptom_add_title),
              onClick = onAddClick,
              icon = painterResource(R.drawable.ic_add),
              modifier =
                  Modifier.elevation1(MaterialTheme.shapes.full).testTag(C.Tag.symptom_journal_add),
          )
        }
      },
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      when {
        uiState.isLoading ->
            CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag(C.Tag.symptom_journal_loading)
            )
        uiState.hasError ->
            LakeMessageState(
                title =
                    caregiverOf?.let {
                      stringResource(R.string.symptom_journal_error_title_for, it)
                    } ?: stringResource(R.string.symptom_journal_error_title),
                message = stringResource(R.string.symptom_error_hint),
                actionLabel = stringResource(R.string.symptom_retry),
                onAction = onRetry,
                actionTestTag = C.Tag.symptom_journal_retry,
                modifier = Modifier.align(Alignment.Center).testTag(C.Tag.symptom_journal_error),
            )
        // The patient a caregiver follows doesn't share their symptoms
        uiState.isNotShared && caregiverOf != null ->
            NotSharedMessage(caregiverOf, CarePermission.SYMPTOMS, Modifier.align(Alignment.Center))
        uiState.days.isEmpty() ->
            EmptyJournal(
                // A caregiver isn't told the symptoms are theirs to show their doctor
                hint =
                    caregiverOf?.let { stringResource(R.string.symptom_journal_empty_hint_for, it) }
                        ?: stringResource(R.string.symptom_journal_empty_hint),
                modifier = Modifier.align(Alignment.Center),
            )
        else -> JournalList(uiState.days, uiState.today, uiState.canAdd, onEntryClick)
      }
    }
  }
}

/** Nothing logged yet: the Empty lake, then what will appear here (DESIGN.md, "Empty"). */
@Composable
private fun EmptyJournal(hint: String, modifier: Modifier = Modifier) {
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .padding(Spacing.xl)
              .testTag(C.Tag.symptom_journal_empty),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(Spacing.sm),
  ) {
    EmptyLake(Modifier.padding(bottom = Spacing.sm))
    Text(
        text = stringResource(R.string.symptom_journal_empty_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
    Text(
        text = hint,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
  }
}

/**
 * The days, newest first, each under its header with its entries in one white card. Each entry
 * stays its own lazy item, so a long day still loads only what is on screen.
 */
@Composable
private fun JournalList(
    days: List<JournalDay>,
    today: LocalDate,
    hasAddButton: Boolean,
    onEntryClick: (String) -> Unit,
) {
  // The week's summary, if something was logged this week
  val week = remember(days, today) { weekSummaryOf(days, today) }
  LazyColumn(
      modifier = Modifier.fillMaxSize().testTag(C.Tag.symptom_journal_list),
      // The bottom padding keeps the last entry above the add button, when there is one
      contentPadding =
          PaddingValues(
              start = Spacing.lg,
              top = Spacing.sm,
              end = Spacing.lg,
              bottom =
                  if (hasAddButton) LakeButtonDefaults.MinHeight + Spacing.lg * 2 else Spacing.lg,
          ),
  ) {
    week?.let { item(key = "week") { WeekCard(it, Modifier.padding(top = Spacing.sm)) } }
    days.forEach { day ->
      item(key = "day-${day.date}") { DayHeader(day.date, today) }
      itemsIndexed(day.entries, key = { _, it -> it.entry.id }) { index, journalEntry ->
        EntryRow(
            journalEntry = journalEntry,
            isFirst = index == 0,
            isLast = index == day.entries.lastIndex,
            onClick = { onEntryClick(journalEntry.entry.id) },
        )
      }
    }
  }
}

/** "Today", "Yesterday" or the date, above the card of that day. */
@Composable
private fun DayHeader(date: LocalDate, today: LocalDate) {
  Text(
      text = dayLabel(date, today),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier =
          Modifier.padding(top = Spacing.md, bottom = Spacing.sm)
              .semantics { heading() }
              .testTag(C.Tag.symptomJournalDay(date.toString())),
  )
}

/**
 * One entry, as a row of its day's card: what it was, its severity and the start of its notes (if
 * given), who added it, and the time. Only the day's first and last rows round their corners, and
 * an inset divider splits the rows.
 */
@Composable
private fun EntryRow(
    journalEntry: JournalEntry,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
  val entry = journalEntry.entry
  val summary = entry.summary()
  val corners = MaterialTheme.shapes.large
  val square = CornerSize(0)
  val shape =
      corners.copy(
          topStart = if (isFirst) corners.topStart else square,
          topEnd = if (isFirst) corners.topEnd else square,
          bottomStart = if (isLast) corners.bottomStart else square,
          bottomEnd = if (isLast) corners.bottomEnd else square,
      )
  Surface(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth().testTag(C.Tag.symptomJournalItem(entry.id)),
      shape = shape,
      color = MaterialTheme.colorScheme.surfaceContainerLowest,
  ) {
    Column {
      if (!isFirst) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = Spacing.lg),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
      }
      Row(
          modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
          horizontalArrangement = Arrangement.spacedBy(Spacing.md),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        SymptomTile()
        Column(Modifier.weight(1f)) {
          Text(entry.title(), style = MaterialTheme.typography.titleMedium)
          summary?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
          }
          // A symptom a caregiver logged for the patient says who did
          journalEntry.addedByName?.let { AddedBy(it) }
        }
        Text(
            text = journalEntry.time.format(symptomTimeFormatter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * The round tile at the start of a row, the same for every entry so its colour never judges it.
 * Decorative: the title says what was logged.
 */
@Composable
private fun SymptomTile() {
  Surface(
      modifier = Modifier.size(TILE_SIZE),
      shape = MaterialTheme.shapes.full,
      color = MaterialTheme.colorScheme.primaryContainer,
      contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Icon(
          painterResource(R.drawable.ic_symptoms),
          contentDescription = null,
          modifier = Modifier.size(TILE_ICON_SIZE),
      )
    }
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

// The round tile of the Figma's journal rows, and its icon
private val TILE_SIZE = 40.dp
private val TILE_ICON_SIZE = 20.dp
