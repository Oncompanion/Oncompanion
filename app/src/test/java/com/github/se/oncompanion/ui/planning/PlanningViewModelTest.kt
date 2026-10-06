package com.github.se.oncompanion.ui.planning

import android.os.Looper
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlanningViewModelTest {
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone)

  private class FakeRepository : PlanningRepository {
    val items = MutableStateFlow<List<PlanningItem>>(emptyList())
    val ranges = mutableListOf<PlanningRange>()
    var fail = false

    override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> {
      ranges.add(range)
      return if (fail) flow { throw IllegalStateException("Read failed") } else items
    }
  }

  private fun drain() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun item(id: String, instant: String) =
      PlanningItem(PlanningSource.Appointment(id), Instant.parse(instant), "Consultation")

  @Test
  fun selectsTodayAndNavigatesAcrossYearBoundary() {
    val repository = FakeRepository()
    val vm = PlanningViewModel(repository, clock, zone)
    drain()
    assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
    assertEquals(LocalDate.of(2026, 9, 28), vm.uiState.value.weekStart)
    assertFalse(vm.uiState.value.isLoading)
    vm.selectDate(LocalDate.of(2026, 12, 31))
    vm.nextWeek()
    drain()
    assertEquals(LocalDate.of(2027, 1, 7), vm.uiState.value.selectedDate)
    vm.previousWeek()
    vm.goToToday()
    drain()
    assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
  }

  @Test
  fun groupsInDisplayZoneAndOrdersEqualTimesByIdentity() {
    val repository = FakeRepository()
    repository.items.value =
        listOf(
            item("b", "2026-10-02T08:00:00Z"),
            item("a", "2026-10-02T08:00:00Z"),
            item("early", "2026-10-01T22:30:00Z"),
            item("tomorrow", "2026-10-02T22:00:00Z"),
            item("outside", "2026-11-02T08:00:00Z"),
        )
    val vm = PlanningViewModel(repository, clock, zone)
    drain()
    assertEquals(
        listOf("appointment:early", "appointment:a", "appointment:b"),
        vm.uiState.value.items.map { it.key },
    )
    vm.selectDate(LocalDate.of(2026, 10, 3))
    assertEquals(listOf("appointment:tomorrow"), vm.uiState.value.items.map { it.key })
    vm.selectDate(LocalDate.of(2026, 10, 4))
    assertTrue(vm.uiState.value.items.isEmpty())
  }

  @Test
  fun errorCanBeRetried() {
    val repository = FakeRepository().apply { fail = true }
    val vm = PlanningViewModel(repository, clock, zone)
    drain()
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)
    repository.fail = false
    vm.retry()
    drain()
    assertFalse(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun weekRangeAccountsForDaylightSaving() {
    val repository = FakeRepository()
    val vm = PlanningViewModel(repository, clock, zone)
    vm.selectDate(LocalDate.of(2026, 10, 25))
    drain()
    val range = repository.ranges.last()
    assertEquals(Instant.parse("2026-10-18T22:00:00Z"), range.startInclusive)
    assertEquals(Instant.parse("2026-10-25T23:00:00Z"), range.endExclusive)
  }

  @Test
  fun navigationStopsAtSupportedYearBoundaries() {
    val vm = PlanningViewModel(FakeRepository(), clock, zone)
    val first = LocalDate.of(1, 1, 1)
    vm.selectDate(first)
    vm.previousWeek()
    assertEquals(first, vm.uiState.value.selectedDate)
    assertFalse(vm.uiState.value.canGoToPreviousWeek)
    val last = LocalDate.of(9999, 12, 31)
    vm.selectDate(last)
    vm.nextWeek()
    assertEquals(last, vm.uiState.value.selectedDate)
    assertFalse(vm.uiState.value.canGoToNextWeek)
    drain()
  }

  @Test
  fun cachedItemsSurviveFlowFailureAndFailedRetry() {
    val cached = item("cached", "2026-10-02T08:00:00Z")
    var reads = 0
    val retryFailure = CompletableDeferred<Unit>()
    val repository =
        object : PlanningRepository {
          override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> = flow {
            if (reads++ == 0) emit(listOf(cached)) else retryFailure.await()
            throw IllegalStateException("Read failed")
          }
        }
    val vm = PlanningViewModel(repository, clock, zone)
    drain()
    assertEquals(listOf(cached), vm.uiState.value.items)
    assertTrue(vm.uiState.value.hasError)
    vm.retry()
    assertEquals(listOf(cached), vm.uiState.value.items)
    assertTrue(vm.uiState.value.isLoading)
    retryFailure.complete(Unit)
    drain()
    assertEquals(listOf(cached), vm.uiState.value.items)
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)
  }
}
