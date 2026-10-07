package com.github.se.oncompanion.ui.planning

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Tab
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Calendar and agenda events supplied by the screen or preview. */
data class PlanningActions(
    val onDateSelected: (LocalDate) -> Unit,
    val onPreviousWeek: () -> Unit,
    val onNextWeek: () -> Unit,
    val onToday: () -> Unit,
    val onRetry: () -> Unit,
    val onAddAppointment: ((LocalDate) -> Unit)? = null,
    val onItemClick: ((PlanningItem) -> Unit)? = null,
)

/** The Planning tab, collecting calendar state and reusing the app's bottom navigation. */
@Composable
fun PlanningScreen(
    navigationActions: NavigationActions,
    viewModel: PlanningViewModel,
    modifier: Modifier = Modifier,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  LifecycleResumeEffect(viewModel) {
    viewModel.refreshToday()
    onPauseOrDispose {}
  }
  PlanningContent(
      state = state,
      today = state.today,
      zoneId = viewModel.zoneId,
      actions =
          PlanningActions(
              viewModel::selectDate,
              viewModel::previousWeek,
              viewModel::nextWeek,
              viewModel::goToToday,
              viewModel::retry,
          ),
      modifier = modifier,
      bottomBar = {
        BottomNavigationBar(Tab.PLANNING, { navigationActions.navigateToTab(it.route) })
      },
  )
}

/** Stateless calendar and agenda; unavailable appointment actions are omitted. */
@Composable
fun PlanningContent(
    state: PlanningUiState,
    today: LocalDate,
    zoneId: ZoneId,
    actions: PlanningActions,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
) {
  val locale = LocalConfiguration.current.locales[0]
  val fullDate = PlanningDates.format(state.selectedDate)
  Scaffold(
      modifier = modifier.fillMaxSize().testTag(C.Tag.planning_screen),
      bottomBar = bottomBar,
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      Column {
        Text(
            stringResource(R.string.planning_title),
            Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineMedium,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
              state.selectedDate.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
              Modifier.weight(1f),
              style = MaterialTheme.typography.titleMedium,
          )
          val previousLabel = stringResource(R.string.planning_previous_week)
          TextButton(
              actions.onPreviousWeek,
              Modifier.testTag(C.Tag.planning_previous).semantics {
                contentDescription = previousLabel
              },
              enabled = state.canGoToPreviousWeek,
          ) {
            Text("‹")
          }
          val todayLabel = stringResource(R.string.planning_go_today)
          TextButton(
              actions.onToday,
              Modifier.testTag(C.Tag.planning_today).semantics { contentDescription = todayLabel },
          ) {
            Text(stringResource(R.string.planning_today))
          }
          val nextLabel = stringResource(R.string.planning_next_week)
          TextButton(
              actions.onNextWeek,
              Modifier.testTag(C.Tag.planning_next).semantics { contentDescription = nextLabel },
              enabled = state.canGoToNextWeek,
          ) {
            Text("›")
          }
        }
        WeekStrip(
            state.weekStart,
            state.selectedDate,
            today,
            locale,
            actions.onDateSelected,
        )
        Text(
            if (state.selectedDate == today) stringResource(R.string.planning_today_date, fullDate)
            else fullDate,
            Modifier.padding(16.dp).testTag(C.Tag.planning_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlanningAgenda(
            state,
            zoneId,
            locale,
            actions.onRetry,
            actions.onItemClick,
            Modifier.weight(1f),
        )
      }
      actions.onAddAppointment?.let { onAdd ->
        val addLabel = stringResource(R.string.planning_add)
        FloatingActionButton(
            onClick = { onAdd(state.selectedDate) },
            modifier =
                Modifier.align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag(C.Tag.planning_add)
                    .semantics { contentDescription = addLabel },
        ) {
          Text("+", style = MaterialTheme.typography.headlineMedium)
        }
      }
    }
  }
}

@Composable
internal fun WeekStrip(
    weekStart: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onDateSelected: (LocalDate) -> Unit,
) {
  Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(C.Tag.planning_week),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    repeat(7) { offset ->
      val date = weekStart.plusDays(offset.toLong())
      WeekDay(date, selectedDate, today, locale, onDateSelected, Modifier.weight(1f))
    }
  }
}

@Composable
private fun WeekDay(
    date: LocalDate,
    selectedDate: LocalDate,
    today: LocalDate,
    locale: Locale,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
  val selected = date == selectedDate
  val supported = PlanningDates.isSupported(date)
  val unavailableLabel = stringResource(R.string.planning_date_unavailable)
  val todayText = stringResource(R.string.planning_today)
  Surface(
      color =
          if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
      contentColor =
          if (selected) MaterialTheme.colorScheme.onPrimary
          else MaterialTheme.colorScheme.onSurface,
      shape = RoundedCornerShape(24.dp),
      border =
          if (date == today && !selected)
              androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
          else null,
      modifier =
          modifier
              .testTag(C.Tag.planningDay(date.toString()))
              .selectable(
                  selected,
                  enabled = supported,
                  role = Role.Tab,
                  onClick = { onDateSelected(date) },
              )
              .semantics(mergeDescendants = true) {
                contentDescription =
                    dayDescription(date, today, supported, todayText, unavailableLabel)
              },
  ) {
    Column(
        Modifier.padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
          date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale),
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          if (supported) String.format(Locale.ROOT, "%02d", date.dayOfMonth) else "—",
          style = MaterialTheme.typography.titleMedium,
      )
    }
  }
}

private fun dayDescription(
    date: LocalDate,
    today: LocalDate,
    supported: Boolean,
    todayText: String,
    unavailableLabel: String,
): String {
  if (!supported) return unavailableLabel
  val formattedDate = PlanningDates.format(date)
  return if (date == today) "$formattedDate, $todayText" else formattedDate
}

@Composable
internal fun PlanningAgenda(
    state: PlanningUiState,
    zoneId: ZoneId,
    locale: Locale,
    onRetry: () -> Unit,
    onItemClick: ((PlanningItem) -> Unit)?,
    modifier: Modifier = Modifier,
    canAdd: Boolean = false,
) {
  // Save scrolling with the tab, but start at the top when a different day is selected.
  val listState =
      rememberSaveable(state.selectedDate.toString(), saver = LazyListState.Saver) {
        LazyListState()
      }
  val timePattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
  Column(modifier.fillMaxWidth()) {
    if (state.isLoading) {
      val label = stringResource(R.string.planning_loading)
      LinearProgressIndicator(
          Modifier.fillMaxWidth().testTag(C.Tag.planning_loading).semantics {
            contentDescription = label
          }
      )
    }
    if (state.hasError) {
      Column(Modifier.padding(16.dp).testTag(C.Tag.planning_error)) {
        Text(stringResource(R.string.planning_error))
        TextButton(onRetry, Modifier.testTag(C.Tag.planning_retry)) {
          Text(stringResource(R.string.planning_retry))
        }
      }
    }
    if (state.items.isEmpty() && !state.isLoading && !state.hasError) {
      Column(
          Modifier.fillMaxSize().padding(24.dp).testTag(C.Tag.planning_empty),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.planning_empty), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (canAdd)
            Text(
                stringResource(R.string.planning_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
            )
      }
    } else {
      LazyColumn(
          Modifier.testTag(C.Tag.planning_list),
          state = listState,
          contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        // A single scrolling agenda keeps untimed medications before all timed entries.
        val medications = state.items.filter { it.source is PlanningSource.Medication }
        val scheduled = state.items.filter { it.source !is PlanningSource.Medication }
        if (medications.isNotEmpty()) {
          item(key = "section:no-time") {
            AgendaHeading(R.string.planning_no_set_time, C.Tag.planning_no_set_time)
          }
          items(medications, key = { it.key }) { item ->
            PlanningRow(item, zoneId, locale, timePattern, onItemClick)
          }
        }
        if (scheduled.isNotEmpty()) {
          item(key = "section:scheduled") {
            AgendaHeading(R.string.planning_scheduled, C.Tag.planning_scheduled)
          }
          items(scheduled, key = { it.key }) { item ->
            PlanningRow(item, zoneId, locale, timePattern, onItemClick)
          }
        }
      }
    }
  }
}

@Composable
private fun AgendaHeading(label: Int, tag: String) {
  Text(
      stringResource(label),
      Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag).semantics { heading() },
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun PlanningRow(
    item: PlanningItem,
    zoneId: ZoneId,
    locale: Locale,
    timePattern: String,
    onItemClick: ((PlanningItem) -> Unit)?,
) {
  val (icon, typeLabel) =
      when (item.source) {
        is PlanningSource.Appointment ->
            R.drawable.ic_planning_event to R.string.planning_type_appointment
        is PlanningSource.Event -> R.drawable.ic_care_circle to R.string.planning_type_event
        is PlanningSource.Medication ->
            R.drawable.ic_planning_medication to R.string.planning_type_medication
      }
  val supportingText =
      if (item.source is PlanningSource.Medication) {
        item.frequency
            ?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.planning_frequency, it) }
            ?: stringResource(R.string.planning_frequency_unspecified)
      } else item.subtitle
  val timed = item.timing as? PlanningTiming.Timed
  ListItem(
      leadingContent = {
        Icon(painterResource(icon), contentDescription = stringResource(typeLabel))
      },
      headlineContent = { Text(item.title) },
      supportingContent = supportingText?.let { text -> { Text(text) } },
      trailingContent =
          timed?.let { value ->
            {
              Text(
                  value.instant
                      .atZone(zoneId)
                      .format(DateTimeFormatter.ofPattern(timePattern, locale)),
                  style = MaterialTheme.typography.labelSmall,
              )
            }
          },
      modifier =
          Modifier.testTag(C.Tag.planningItem(item.key))
              .then(
                  if (onItemClick == null) Modifier else Modifier.clickable { onItemClick(item) }
              ),
  )
}
