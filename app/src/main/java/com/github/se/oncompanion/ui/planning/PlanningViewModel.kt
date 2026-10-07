package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningTiming
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

/** Selected calendar day and the agenda currently available for it. */
data class PlanningUiState(
    val selectedDate: LocalDate,
    val weekStart: LocalDate,
    val items: List<PlanningItem> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val today: LocalDate = selectedDate,
) {
  val canGoToPreviousWeek: Boolean
    get() = PlanningDates.isSupported(selectedDate.minusWeeks(1))

  val canGoToNextWeek: Boolean
    get() = PlanningDates.isSupported(selectedDate.plusWeeks(1))
}

/** Owns calendar selection; presentation and date labels belong to the view. */
class PlanningViewModel(
    private val repository: PlanningRepository,
    private val clock: Clock,
    val zoneId: ZoneId,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
  private val today = LocalDate.now(clock.withZone(zoneId))
  private val initialDate =
      savedStateHandle.get<String>(SELECTED_DATE)?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf(PlanningDates::isSupported)
      } ?: today
  private val mutableState =
      MutableStateFlow(PlanningUiState(initialDate, mondayOf(initialDate), today = today))
  val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()
  private var weekItems: List<PlanningItem> = emptyList()
  private var observation: Job? = null

  init {
    savedStateHandle[SELECTED_DATE] = initialDate.toString()
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
    observation = viewModelScope.launch {
      try {
        repository.observeItems(range).collect { items ->
          weekItems = items.filter { it.timing in range }
          mutableState.value = mutableState.value.copy(isLoading = false, hasError = false)
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
    mutableState.value =
        mutableState.value.copy(
            items =
                weekItems
                    .filter { it.timing.dateIn(zoneId) == selected }
                    .sortedWith(
                        compareBy<PlanningItem> { it.timing is PlanningTiming.Timed }
                            .thenBy { (it.timing as? PlanningTiming.Timed)?.instant }
                            .thenBy { if (it.timing is PlanningTiming.DateOnly) it.title else "" }
                            .thenBy { it.key }
                    )
        )
  }

  private companion object {
    const val SELECTED_DATE = "planning_selected_date"
  }

  private fun mondayOf(date: LocalDate): LocalDate =
      date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
