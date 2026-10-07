package com.github.se.oncompanion.ui.planning

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelTest {
  private val zone = ZoneId.of("Europe/Zurich")
  private val clock = Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone)

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setMain() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun resetMain() {
    Dispatchers.resetMain()
  }

  private fun drain() {
    dispatcher.scheduler.runCurrent()
  }

  private fun item(id: String, instant: String) =
      PlanningItem(
          PlanningSource.Appointment(id),
          PlanningTiming.Timed(Instant.parse(instant)),
          "Consultation",
      )

  @Test
  fun selectsTodayAndNavigatesAcrossYearBoundary() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
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
  fun groupsInDisplayZoneAndOrdersEqualTimesByIdentity() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        repository.items.value =
            listOf(
                item("b", "2026-10-02T08:00:00Z"),
                item("a", "2026-10-02T08:00:00Z"),
                item("early", "2026-10-01T22:30:00Z"),
                item("tomorrow", "2026-10-02T22:00:00Z"),
                item("outside", "2026-11-02T08:00:00Z"),
            )
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
        drain()
        assertEquals(
            listOf("early", "a", "b"),
            vm.uiState.value.items.map { (it.source as PlanningSource.Appointment).appointmentId },
        )
        vm.selectDate(LocalDate.of(2026, 10, 3))
        assertEquals(
            listOf("tomorrow"),
            vm.uiState.value.items.map { (it.source as PlanningSource.Appointment).appointmentId },
        )
        vm.selectDate(LocalDate.of(2026, 10, 4))
        assertTrue(vm.uiState.value.items.isEmpty())
      }

  @Test
  fun errorCanBeRetried() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository().apply { fail = true }
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
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
  fun weekRangeAccountsForDaylightSaving() =
      runTest(dispatcher) {
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
        vm.selectDate(LocalDate.of(2026, 10, 25))
        drain()
        val range = repository.ranges.last()
        assertEquals(Instant.parse("2026-10-18T22:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-10-25T23:00:00Z"), range.endExclusive)
      }

  @Test
  fun navigationStopsAtSupportedYearBoundaries() =
      runTest(dispatcher) {
        val vm = PlanningViewModel(FakePlanningRepository(), clock, zone, SavedStateHandle())
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
  fun cachedItemsSurviveFlowFailureAndFailedRetry() =
      runTest(dispatcher) {
        val cached = item("cached", "2026-10-02T08:00:00Z")
        var reads = 0
        val retryFailure = CompletableDeferred<Unit>()
        val repository =
            FakePlanningRepository().apply {
              observation = {
                flow {
                  if (reads++ == 0) emit(listOf(cached)) else retryFailure.await()
                  throw IllegalStateException("Read failed")
                }
              }
            }
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
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

  @Test
  fun initialSelectionIsSavedBeforeAnyCalendarAction() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        PlanningViewModel(FakePlanningRepository(), clock, zone, saved)
        drain()
        assertEquals("2026-10-02", saved.get<String>("planning_selected_date"))
      }

  @Test
  fun restoresSelectedDateAndObservesItsWeek() =
      runTest(dispatcher) {
        val saved = SavedStateHandle()
        val original = PlanningViewModel(FakePlanningRepository(), clock, zone, saved)
        val selected = LocalDate.of(2027, 1, 7)
        original.selectDate(selected)
        val restoredState =
            SavedStateHandle(
                mapOf("planning_selected_date" to saved.get<String>("planning_selected_date"))
            )
        val repository = FakePlanningRepository()
        val restored = PlanningViewModel(repository, clock, zone, restoredState)
        drain()
        assertEquals(selected, restored.uiState.value.selectedDate)
        assertEquals(LocalDate.of(2027, 1, 4), restored.uiState.value.weekStart)
        assertEquals(LocalDate.of(2026, 10, 2), restored.uiState.value.today)
        assertEquals(1, repository.ranges.size)
      }

  @Test
  fun invalidSavedDatesFallBackToToday() =
      runTest(dispatcher) {
        listOf("invalid", "0000-01-01", "+10000-01-01").forEach { value ->
          val vm =
              PlanningViewModel(
                  FakePlanningRepository(),
                  clock,
                  zone,
                  SavedStateHandle(mapOf("planning_selected_date" to value)),
              )
          assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        }
        drain()
      }

  @Test
  fun midnightRefreshKeepsSelectionAndDoesNotRestartObservation() =
      runTest(dispatcher) {
        var now = Instant.parse("2026-10-02T21:59:00Z")
        val movingClock =
            object : Clock() {
              override fun getZone(): ZoneId = zone

              override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)

              override fun instant(): Instant = now
            }
        val repository = FakePlanningRepository()
        val vm = PlanningViewModel(repository, movingClock, zone, SavedStateHandle())
        drain()
        now = Instant.parse("2026-10-02T22:01:00Z")
        vm.refreshToday()
        assertEquals(LocalDate.of(2026, 10, 3), vm.uiState.value.today)
        assertEquals(LocalDate.of(2026, 10, 2), vm.uiState.value.selectedDate)
        assertEquals(1, repository.ranges.size)
        vm.goToToday()
        assertEquals(LocalDate.of(2026, 10, 3), vm.uiState.value.selectedDate)
        assertEquals(1, repository.ranges.size)
      }

  @Test
  fun mixedEntriesKeepMedicationsFirstAndObserveChangesWithinTheWeek() =
      runTest(dispatcher) {
        val date = LocalDate.of(2026, 10, 2)
        fun medication(id: String, day: LocalDate = date, title: String = "Medication") =
            PlanningItem(
                PlanningSource.Medication("prescription", id),
                PlanningTiming.DateOnly(day),
                title,
                frequency = "Twice a day",
            )
        val first = medication("a", title = "A medication")
        val tie = medication("b", title = "A medication")
        val last = medication("c", title = "Z medication")
        val nextDay = first.copy(timing = PlanningTiming.DateOnly(date.plusDays(1)))
        val appointment = item("early", "2026-10-01T22:01:00Z")
        val event =
            PlanningItem(
                PlanningSource.Event("event"),
                PlanningTiming.Timed(Instant.parse("2026-10-02T12:00:00Z")),
                "Workshop",
            )
        val repository =
            FakePlanningRepository().apply {
              items.value =
                  listOf(
                      event,
                      last,
                      tie,
                      appointment,
                      first,
                      nextDay,
                      medication("outside", date.plusWeeks(1)),
                  )
            }
        val vm = PlanningViewModel(repository, clock, zone, SavedStateHandle())
        drain()
        assertEquals(listOf(first, tie, last, appointment, event), vm.uiState.value.items)
        assertEquals(1, repository.ranges.size)
        repository.items.value = listOf(first.copy(frequency = "As recorded"), event)
        drain()
        assertEquals("As recorded", vm.uiState.value.items.first().frequency)
        assertEquals(2, vm.uiState.value.items.size)
        assertEquals(1, repository.ranges.size)
        repository.items.value = listOf(first, nextDay)
        drain()
        vm.selectDate(date.plusDays(1))
        assertEquals(listOf(nextDay), vm.uiState.value.items)
        assertEquals(1, repository.ranges.size)
      }

  @Test
  fun dateOnlyEntriesStayOnTheirDayInAnotherDisplayZone() =
      runTest(dispatcher) {
        val date = LocalDate.of(2026, 10, 2)
        val medication =
            PlanningItem(
                PlanningSource.Medication("p", "m"),
                PlanningTiming.DateOnly(date),
                "Medication",
            )
        val repository = FakePlanningRepository().apply { items.value = listOf(medication) }
        val vm =
            PlanningViewModel(repository, clock, ZoneId.of("Pacific/Honolulu"), SavedStateHandle())
        drain()
        assertEquals(listOf(medication), vm.uiState.value.items)
        vm.selectDate(date.minusDays(1))
        assertTrue(vm.uiState.value.items.isEmpty())
      }
}
