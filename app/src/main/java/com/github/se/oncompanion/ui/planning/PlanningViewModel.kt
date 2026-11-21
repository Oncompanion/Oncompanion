// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.ui.common.currentDay
import java.text.Collator
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Selected calendar day, the period loaded around it, and the agenda currently available.
 *
 * @property period the days of the chosen view around [selectedDate]; its range is what is loaded
 * @property itemsByDay every loaded entry under the day it starts in the display zone, in agenda
 *   order; only days with entries are keys. An entry that goes past midnight is listed on its start
 *   day only: showing it on each day it covers is up to the view that needs it (#555)
 * @property untimedItems the selected day's medications, by name
 * @property scheduledItems the selected day's timed entries, by time
 * @property isNotShared in caregiver mode, the patient doesn't share their planning: no entries
 * @property canAdd whether adding an appointment is offered: always in the user's own planning, and
 *   in caregiver mode once the patient's planning shows and they let the caregiver add (US-41)
 */
data class PlanningUiState(
    val selectedDate: LocalDate,
    val period: PlanningPeriod = PlanningPeriod.of(PlanningView.LIST, selectedDate),
    val itemsByDay: Map<LocalDate, List<PlanningItem>> = emptyMap(),
    val untimedItems: List<PlanningItem> = emptyList(),
    val scheduledItems: List<PlanningItem> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val today: LocalDate = selectedDate,
    val isNotShared: Boolean = false,
    val canAdd: Boolean = true,
) {
  /** The complete agenda in section order, derived from the two displayed sections. */
  val items: List<PlanningItem>
    get() = untimedItems + scheduledItems

  /** The view the period belongs to. */
  val view: PlanningView
    get() = period.view

  /** The Monday of the selected day's week, which the List view's strip shows. */
  val weekStart: LocalDate
    get() = PlanningPeriod.of(PlanningView.LIST, selectedDate).start

  /** Whether the previous period is within years 1 to 9999. */
  val canGoToPreviousPeriod: Boolean
    get() = period.moved(selectedDate, -1) != null

  /** Whether the next period is within years 1 to 9999. */
  val canGoToNextPeriod: Boolean
    get() = period.moved(selectedDate, 1) != null
}

/**
 * Owns calendar selection: the selected day and the period of the chosen view, which it loads.
 * Presentation and date labels belong to the view.
 *
 * @param patientUid in caregiver mode, the patient whose planning is shown; null for the user's own
 */
class PlanningViewModel(
    private val observePlanning: ObserveCarePlanning = ObserveCarePlanning(),
    private val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val savedStateHandle: SavedStateHandle,
    private val patientUid: String? = null,
) : ViewModel() {
  private val today = LocalDate.now(clock.withZone(zoneId))
  private val initialDate =
      savedStateHandle.get<String>(SELECTED_DATE)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf(PlanningDates::isSupported)
      } ?: today
  private val initialView =
      savedStateHandle.get<String>(VIEW)?.let { name ->
        PlanningView.entries.find { it.name == name }
      } ?: PlanningView.LIST
  // A saved period that no longer holds the restored day is computed again
  private val initialPeriod =
      savedStateHandle
          .get<String>(PERIOD_START)
          ?.let { runCatching { PlanningPeriod(initialView, LocalDate.parse(it)) }.getOrNull() }
          ?.takeIf { initialDate in it } ?: PlanningPeriod.of(initialView, initialDate)
  private val mutableState =
      MutableStateFlow(
          PlanningUiState(
              initialDate,
              initialPeriod,
              today = today,
              canAdd = patientUid == null,
          )
      )
  val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()
  private var observation: Job? = null

  init {
    save(mutableState.value)
    // The observation follows account and access changes itself
    observePeriod()
  }

  /**
   * Selects a day. The period stays while it holds the day (three days don't move under a day
   * picked inside them); otherwise the view's period of that day is loaded.
   */
  fun selectDate(date: LocalDate) {
    require(date.year in 1..9999) { "Date must have a four-digit positive year" }
    val period = mutableState.value.period
    select(date, period.takeIf { date in it } ?: PlanningPeriod.of(period.view, date))
  }

  /** Shows [view], keeping the selected day; three days then start on it. */
  fun selectView(view: PlanningView) {
    val state = mutableState.value
    if (view != state.view) select(state.selectedDate, PlanningPeriod.of(view, state.selectedDate))
  }

  /** Moves to the previous period, if it is within the supported years. */
  fun previousPeriod() = move(-1)

  /** Moves to the next period, if it is within the supported years. */
  fun nextPeriod() = move(1)

  /** Updates the today marker without changing the selected day or restarting observation. */
  fun refreshToday() {
    mutableState.value = mutableState.value.copy(today = LocalDate.now(clock.withZone(zoneId)))
  }

  /**
   * Keeps the today marker right while Planning is open: updates it now, then at each midnight,
   * until the returned job is cancelled. Like [refreshToday], it keeps the selected day. The screen
   * runs it while it is shown.
   */
  fun followToday(): Job = viewModelScope.launch {
    currentDay(clock.withZone(zoneId)).collect { today ->
      mutableState.value = mutableState.value.copy(today = today)
    }
  }

  /** Selects the current day, including after the screen has crossed midnight. */
  fun goToToday() {
    refreshToday()
    selectDate(mutableState.value.today)
  }

  /** Retries observation while keeping previously displayed items. */
  fun retry() = observePeriod(preserveItems = true)

  private fun move(steps: Int) {
    val state = mutableState.value
    state.period.moved(state.selectedDate, steps)?.let { select(it.selectedDate, it.period) }
  }

  /** Selects [date] in [period], loading again only when the days to load change. */
  private fun select(date: LocalDate, period: PlanningPeriod) {
    val previous = mutableState.value
    val next = previous.copy(selectedDate = date, period = period)
    mutableState.value = next
    save(next)
    // List and Week of the same Monday load the same days: no new read
    if (period.range(zoneId) != previous.period.range(zoneId)) observePeriod() else publishItems()
  }

  private fun save(state: PlanningUiState) {
    savedStateHandle[SELECTED_DATE] = state.selectedDate.toString()
    savedStateHandle[VIEW] = state.view.name
    savedStateHandle[PERIOD_START] = state.period.start.toString()
  }

  private fun observePeriod(preserveItems: Boolean = false) {
    observation?.cancel()
    mutableState.value =
        mutableState.value.copy(
            isLoading = true,
            hasError = false,
            itemsByDay = if (preserveItems) mutableState.value.itemsByDay else emptyMap(),
        )
    publishItems()
    val range = mutableState.value.period.range(zoneId)
    // A retry keeps the displayed items until the first answer, whatever it is; after that, an
    // account change clears them again
    var keepItems = preserveItems
    observation = viewModelScope.launch {
      try {
        observePlanning(patientUid, range).collect { planning ->
          // A failure keeps what is shown, like a retry until new entries arrive
          if (
              planning !is CarePlanning.Failed && (planning !is CarePlanning.Loading || !keepItems)
          ) {
            val items =
                (planning as? CarePlanning.Shown)?.items?.filter { it.timing in range }.orEmpty()
            mutableState.value = mutableState.value.copy(itemsByDay = items.groupedByDay(zoneId))
          }
          if (planning !is CarePlanning.Loading) keepItems = false
          mutableState.value =
              mutableState.value.copy(
                  isLoading = planning is CarePlanning.Loading,
                  hasError = planning is CarePlanning.Failed,
                  isNotShared = planning is CarePlanning.NotShared,
                  // A caregiver only gets "Add" with the patient's planning on screen
                  canAdd = patientUid == null || (planning as? CarePlanning.Shown)?.canAdd == true,
              )
          publishItems()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.value = mutableState.value.copy(isLoading = false, hasError = true)
      }
    }
  }

  /** Shows the selected day's entries, already in agenda order in [PlanningUiState.itemsByDay]. */
  private fun publishItems() {
    val state = mutableState.value
    val (untimed, scheduled) =
        state.itemsByDay[state.selectedDate].orEmpty().partition {
          it.timing is PlanningTiming.DateOnly
        }
    mutableState.value = state.copy(untimedItems = untimed, scheduledItems = scheduled)
  }

  private companion object {
    const val SELECTED_DATE = "planning_selected_date"
    const val VIEW = "planning_view"
    const val PERIOD_START = "planning_period_start"
  }
}

/**
 * [this] grouped under the day each entry starts in [zoneId], days in order, each in agenda order:
 * medications first by name in the phone's locale (case and accents included), then timed entries
 * by time; the key keeps entries with the same name or time in a stable order. Medications keep
 * their calendar day in any zone. An entry is listed on its start day only.
 */
internal fun List<PlanningItem>.groupedByDay(zoneId: ZoneId): Map<LocalDate, List<PlanningItem>> {
  val titles = Collator.getInstance()
  val agendaOrder =
      compareBy<PlanningItem> { it.timing is PlanningTiming.Timed }
          .thenBy { (it.timing as? PlanningTiming.Timed)?.instant }
          .thenBy(titles) { if (it.timing is PlanningTiming.DateOnly) it.title else "" }
          .thenBy { it.key }
  return groupBy { it.timing.dateIn(zoneId) }
      .toSortedMap()
      .mapValues { (_, items) -> items.sortedWith(agendaOrder) }
}
