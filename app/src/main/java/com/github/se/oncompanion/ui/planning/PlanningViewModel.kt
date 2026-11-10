// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.planning.CarePlanning
import com.github.se.oncompanion.domain.planning.ObserveCarePlanning
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.ui.common.currentDay
import java.text.Collator
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Selected calendar day and the agenda currently available for it.
 *
 * @property isNotShared in caregiver mode, the patient doesn't share their planning: no entries
 * @property canAdd whether adding an appointment is offered: always in the user's own planning, and
 *   in caregiver mode once the patient's planning shows and they let the caregiver add (US-41)
 */
data class PlanningUiState(
    val selectedDate: LocalDate,
    val weekStart: LocalDate,
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

  val canGoToPreviousWeek: Boolean
    get() = PlanningDates.isSupported(selectedDate.minusWeeks(1))

  val canGoToNextWeek: Boolean
    get() = PlanningDates.isSupported(selectedDate.plusWeeks(1))
}

/**
 * Owns calendar selection; presentation and date labels belong to the view.
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
  private val mutableState =
      MutableStateFlow(
          PlanningUiState(
              initialDate,
              mondayOf(initialDate),
              today = today,
              canAdd = patientUid == null,
          )
      )
  val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()
  private var weekItems: List<PlanningItem> = emptyList()
  private var observation: Job? = null

  init {
    savedStateHandle[SELECTED_DATE] = initialDate.toString()
    // The observation follows account and access changes itself
    observeWeek()
  }

  /** Selects a day, observing a new week only when necessary. */
  fun selectDate(date: LocalDate) {
    require(date.year in 1..9999) { "Date must have a four-digit positive year" }
    savedStateHandle[SELECTED_DATE] = date.toString()
    val week = mondayOf(date)
    val changedWeek = week != mutableState.value.weekStart
    mutableState.value = mutableState.value.copy(selectedDate = date, weekStart = week)
    if (changedWeek) observeWeek() else publishItems()
  }

  /** Moves selection to the previous supported week. */
  fun previousWeek() {
    if (mutableState.value.canGoToPreviousWeek)
        selectDate(mutableState.value.selectedDate.minusWeeks(1))
  }

  /** Moves selection to the next supported week. */
  fun nextWeek() {
    if (mutableState.value.canGoToNextWeek) selectDate(mutableState.value.selectedDate.plusWeeks(1))
  }

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
  fun retry() = observeWeek(preserveItems = true)

  private fun observeWeek(preserveItems: Boolean = false) {
    observation?.cancel()
    if (!preserveItems) weekItems = emptyList()
    mutableState.value = mutableState.value.copy(isLoading = true, hasError = false)
    publishItems()
    val week = mutableState.value.weekStart
    val range =
        PlanningRange(
            week,
            week.plusWeeks(1),
            zoneId,
        )
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
            weekItems =
                (planning as? CarePlanning.Shown)?.items?.filter { it.timing in range }.orEmpty()
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

  private fun publishItems() {
    val selected = mutableState.value.selectedDate
    // Compare medication names using the phone's locale, including case and accents.
    val titleCollator = Collator.getInstance()
    // Group by timing once so the view renders the same sections that define ordering.
    val (untimed, scheduled) =
        weekItems
            .filter { it.timing.dateIn(zoneId) == selected }
            .partition { it.timing is PlanningTiming.DateOnly }
    mutableState.value =
        mutableState.value.copy(
            untimedItems =
                untimed.sortedWith(
                    compareBy<PlanningItem, String>(titleCollator) { it.title }.thenBy { it.key }
                ),
            scheduledItems =
                scheduled.sortedWith(
                    compareBy<PlanningItem> { (it.timing as PlanningTiming.Timed).instant }
                        .thenBy { it.key }
                ),
        )
  }

  private companion object {
    const val SELECTED_DATE = "planning_selected_date"
  }

  private fun mondayOf(date: LocalDate): LocalDate =
      date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
