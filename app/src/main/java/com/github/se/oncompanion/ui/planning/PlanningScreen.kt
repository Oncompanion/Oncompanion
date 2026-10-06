package com.github.se.oncompanion.ui.planning

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.resources.C
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Stateless feature content; the app navigation owns its bottom bar and outer insets. */
@Composable
fun PlanningScreen(
    state: PlanningUiState,
    today: LocalDate,
    zoneId: ZoneId,
    onDateSelected: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    onRetry: () -> Unit,
    onAddAppointment: (LocalDate) -> Unit,
    onItemClick: (PlanningItem) -> Unit,
    modifier: Modifier = Modifier,
) {
  val locale = LocalConfiguration.current.locales[0]
  val fullDate = PlanningDates.format(state.selectedDate)
  Surface(modifier.fillMaxSize().testTag(C.Tag.planning_screen)) {
    Box(Modifier.fillMaxSize()) {
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
              onPreviousWeek,
              Modifier.testTag(C.Tag.planning_previous).semantics {
                contentDescription = previousLabel
              },
              enabled = state.canGoToPreviousWeek,
          ) {
            Text("‹")
          }
          val todayLabel = stringResource(R.string.planning_go_today)
          TextButton(
              onToday,
              Modifier.testTag(C.Tag.planning_today).semantics { contentDescription = todayLabel },
          ) {
            Text(stringResource(R.string.planning_today))
          }
          val nextLabel = stringResource(R.string.planning_next_week)
          TextButton(
              onNextWeek,
              Modifier.testTag(C.Tag.planning_next).semantics { contentDescription = nextLabel },
              enabled = state.canGoToNextWeek,
          ) {
            Text("›")
          }
        }
        WeekStrip(state.weekStart, state.selectedDate, today, locale, onDateSelected)
        Text(
            if (state.selectedDate == today) stringResource(R.string.planning_today_date, fullDate)
            else fullDate,
            Modifier.padding(16.dp).testTag(C.Tag.planning_heading),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlanningAgenda(state, zoneId, locale, onRetry, onItemClick, Modifier.weight(1f))
      }
      val addLabel = stringResource(R.string.planning_add)
      FloatingActionButton(
          onClick = { onAddAppointment(state.selectedDate) },
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
      val selected = date == selectedDate
      val supported = PlanningDates.isSupported(date)
      val unavailableLabel = stringResource(R.string.planning_date_unavailable)
      val todayText = stringResource(R.string.planning_today)
      Surface(
          color =
              if (selected) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.surface,
          contentColor =
              if (selected) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.onSurface,
          shape = RoundedCornerShape(24.dp),
          border =
              if (date == today && !selected)
                  androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
              else null,
          modifier =
              Modifier.weight(1f)
                  .testTag(C.Tag.planningDay(date.toString()))
                  .selectable(
                      selected,
                      enabled = supported,
                      role = Role.Tab,
                      onClick = { onDateSelected(date) },
                  )
                  .semantics(mergeDescendants = true) {
                    contentDescription =
                        if (supported)
                            PlanningDates.format(date) + if (date == today) ", $todayText" else ""
                        else unavailableLabel
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
  }
}

@Composable
internal fun PlanningAgenda(
    state: PlanningUiState,
    zoneId: ZoneId,
    locale: Locale,
    onRetry: () -> Unit,
    onItemClick: (PlanningItem) -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Text(
            stringResource(R.string.planning_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      LazyColumn(
          Modifier.testTag(C.Tag.planning_list),
          contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        items(state.items, key = { it.key }) { item ->
          ListItem(
              leadingContent = {
                Icon(painterResource(R.drawable.ic_planning_event), contentDescription = null)
              },
              headlineContent = { Text(item.title) },
              supportingContent = item.subtitle?.let { subtitle -> { Text(subtitle) } },
              trailingContent = {
                Text(
                    item.scheduledAt
                        .atZone(zoneId)
                        .format(DateTimeFormatter.ofPattern(timePattern, locale)),
                    style = MaterialTheme.typography.labelSmall,
                )
              },
              modifier =
                  Modifier.testTag(C.Tag.planningItem(item.key)).clickable { onItemClick(item) },
          )
        }
      }
    }
  }
}
