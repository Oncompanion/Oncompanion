// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.common.AddedBy
import com.github.se.oncompanion.ui.common.LakeButton
import com.github.se.oncompanion.ui.common.LakeButtonStyle
import com.github.se.oncompanion.ui.common.NotSharedMessage
import com.github.se.oncompanion.ui.common.isCompactHeight
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
    val canOpenItem: (PlanningItem) -> Boolean = { true },
)

/**
 * The Planning tab, collecting calendar state and reusing the app's bottom navigation.
 *
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun PlanningScreen(
    navigationActions: NavigationActions,
    viewModel: PlanningViewModel,
    modifier: Modifier = Modifier,
    onAddAppointment: ((LocalDate) -> Unit)? = null,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenMedication: (String, String) -> Unit,
    caregiverOf: String? = null,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val openItem: (PlanningItem) -> Unit = {
    openPlanningItem(it, onOpenAppointment, onOpenEvent, onOpenMedication)
  }
  // Keeps the today marker right while Planning is on screen: refreshes when it comes back, then
  // at each midnight
  LifecycleResumeEffect(viewModel) {
    val following = viewModel.followToday()
    onPauseOrDispose { following.cancel() }
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
              onAddAppointment = onAddAppointment,
              onItemClick = openItem,
          ),
      modifier = modifier,
      bottomBar = {
        BottomNavigationBar(Tab.PLANNING, { navigationActions.navigateToTab(it.route) })
      },
      caregiverOf = caregiverOf,
  )
}

/** Opens the page of the feature that owns [item], preserving its source identifiers. */
internal fun openPlanningItem(
    item: PlanningItem,
    onOpenAppointment: (String) -> Unit,
    onOpenEvent: (String) -> Unit,
    onOpenMedication: (String, String) -> Unit,
) {
  when (val source = item.source) {
    is PlanningSource.Appointment -> onOpenAppointment(source.appointmentId)
    is PlanningSource.Event -> onOpenEvent(source.eventId)
    is PlanningSource.Medication -> onOpenMedication(source.prescriptionId, source.medicationId)
  }
}

/**
 * Stateless calendar and agenda; unavailable appointment actions are omitted.
 *
 * @param caregiverOf the first name of the patient a caregiver follows, `null` for a patient
 */
@Composable
fun PlanningContent(
    state: PlanningUiState,
    today: LocalDate,
    zoneId: ZoneId,
    actions: PlanningActions,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    caregiverOf: String? = null,
) {
  val locale = LocalConfiguration.current.locales[0]
  val compact = isCompactHeight()
  Scaffold(
      modifier = modifier.fillMaxSize().testTag(C.Tag.planning_screen),
      bottomBar = bottomBar,
  ) { innerPadding ->
    Box(Modifier.fillMaxSize().padding(innerPadding)) {
      Column {
        if (!compact) PlanningHeader(state, today, locale, actions)
        PlanningAgenda(
            state,
            zoneId,
            locale,
            actions.onRetry,
            actions.onItemClick,
            Modifier.weight(1f),
            canOpenItem = actions.canOpenItem,
            header =
                if (compact) {
                  { PlanningHeader(state, today, locale, actions) }
                } else null,
            caregiverOf = caregiverOf,
        )
      }
      actions.onAddAppointment?.let { onAdd ->
        // A caregiver only gets "+" when the patient lets them add appointments (US-41)
        if (!state.canAdd) return@let
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

/** Shared calendar controls stay fixed in portrait and join the agenda on short windows. */
@Composable
private fun PlanningHeader(
    state: PlanningUiState,
    today: LocalDate,
    locale: Locale,
    actions: PlanningActions,
) {
  val fullDate = PlanningDates.format(state.selectedDate)
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
    canOpenItem: (PlanningItem) -> Boolean = { true },
    header: (@Composable () -> Unit)? = null,
    caregiverOf: String? = null,
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
        LakeButton(
            text = stringResource(R.string.planning_retry),
            onClick = onRetry,
            style = LakeButtonStyle.Text,
            modifier = Modifier.testTag(C.Tag.planning_retry),
        )
      }
    }
    if (state.isNotShared && caregiverOf != null) {
      // Keep calendar navigation available without showing the patient's unshared entries.
      Column(
          Modifier.fillMaxWidth()
              .then(
                  if (header != null) Modifier.verticalScroll(rememberScrollState()) else Modifier
              )
      ) {
        header?.invoke()
        NotSharedMessage(caregiverOf, CarePermission.PLANNING)
      }
    } else if (
        (state.untimedItems.isEmpty() && state.scheduledItems.isEmpty()) &&
            !state.isLoading &&
            !state.hasError
    ) {
      Column(
          Modifier.fillMaxSize()
              .then(
                  if (header != null) Modifier.verticalScroll(rememberScrollState()) else Modifier
              )
              .padding(bottom = if (header != null) 96.dp else 0.dp),
          verticalArrangement = if (header == null) Arrangement.Center else Arrangement.Top,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        header?.invoke()
        Column(
            Modifier.padding(24.dp).testTag(C.Tag.planning_empty),
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
      }
    } else {
      LazyColumn(
          Modifier.testTag(C.Tag.planning_list),
          state = listState,
          contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        // Compact windows give the header and agenda one viewport, without nested lazy lists.
        if (header != null) item(key = "calendar-header") { header() }
        // A single scrolling agenda keeps untimed medications before all timed entries.
        if (state.untimedItems.isNotEmpty()) {
          item(key = "section:no-time") {
            AgendaHeading(R.string.planning_no_set_time, C.Tag.planning_no_set_time)
          }
          items(state.untimedItems, key = { it.key }) { item ->
            PlanningRow(item, zoneId, locale, timePattern, onItemClick.takeIf { canOpenItem(item) })
          }
        }
        if (state.scheduledItems.isNotEmpty()) {
          item(key = "section:scheduled") {
            AgendaHeading(R.string.planning_scheduled, C.Tag.planning_scheduled)
          }
          items(state.scheduledItems, key = { it.key }) { item ->
            PlanningRow(item, zoneId, locale, timePattern, onItemClick.takeIf { canOpenItem(item) })
          }
        }
      }
    }
  }
}

@Composable
private fun AgendaHeading(@StringRes label: Int, tag: String) {
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
            R.drawable.ic_calendar_month to R.string.planning_type_appointment
        is PlanningSource.Event -> R.drawable.ic_event to R.string.planning_type_event
        is PlanningSource.Medication ->
            R.drawable.ic_medication to R.string.planning_type_medication
      }
  val supportingText = item.supportingText()
  val timed = item.timing as? PlanningTiming.Timed
  ListItem(
      leadingContent = {
        Icon(painterResource(icon), contentDescription = stringResource(typeLabel))
      },
      headlineContent = { Text(item.title) },
      supportingContent =
          if (supportingText == null && item.addedByName == null) null
          else {
            {
              Column {
                supportingText?.let { Text(it) }
                // An appointment a caregiver added for the patient says who did (US-41)
                item.addedByName?.let { AddedBy(it) }
              }
            }
          },
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

/** Appointments may have no supporting text; medications always have a frequency fallback. */
@Composable
private fun PlanningItem.supportingText(): String? {
  if (source !is PlanningSource.Medication) return subtitle
  return frequency
      ?.takeIf { it.isNotBlank() }
      ?.let { stringResource(R.string.planning_frequency, it) }
      ?: stringResource(R.string.planning_frequency_unspecified)
}
