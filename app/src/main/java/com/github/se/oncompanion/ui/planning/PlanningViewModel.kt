package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
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

data class PlanningUiState(
    val selectedDate: LocalDate,
    val weekStart: LocalDate,
    val items: List<PlanningItem> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
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
    private val zoneId: ZoneId,
) : ViewModel() {
  private val today = LocalDate.now(clock.withZone(zoneId))
  private val mutableState = MutableStateFlow(PlanningUiState(today, mondayOf(today)))
  val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()
  private var weekItems: List<PlanningItem> = emptyList()
  private var observation: Job? = null

  init {
    observeWeek()
  }

  fun selectDate(date: LocalDate) {
    require(date.year in 1..9999) { "Date must have a four-digit positive year" }
    val week = mondayOf(date)
    val changedWeek = week != mutableState.value.weekStart
    mutableState.value = mutableState.value.copy(selectedDate = date, weekStart = week)
    if (changedWeek) observeWeek() else publishItems()
  }

  fun previousWeek() {
    if (mutableState.value.canGoToPreviousWeek)
        selectDate(mutableState.value.selectedDate.minusWeeks(1))
  }

  fun nextWeek() {
    if (mutableState.value.canGoToNextWeek) selectDate(mutableState.value.selectedDate.plusWeeks(1))
  }

  fun goToToday() = selectDate(LocalDate.now(clock.withZone(zoneId)))

  fun retry() = observeWeek(preserveItems = true)

  private fun observeWeek(preserveItems: Boolean = false) {
    observation?.cancel()
    if (!preserveItems) weekItems = emptyList()
    mutableState.value = mutableState.value.copy(isLoading = true, hasError = false)
    publishItems()
    val week = mutableState.value.weekStart
    val range =
        PlanningRange(
            week.atStartOfDay(zoneId).toInstant(),
            week.plusWeeks(1).atStartOfDay(zoneId).toInstant(),
        )
    observation = viewModelScope.launch {
      try {
        repository.observeItems(range).collect { items ->
          weekItems = items.filter { it.scheduledAt in range }
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
                    .filter { it.scheduledAt.atZone(zoneId).toLocalDate() == selected }
                    .sortedWith(compareBy<PlanningItem> { it.scheduledAt }.thenBy { it.key })
        )
  }

  private fun mondayOf(date: LocalDate): LocalDate =
      date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
