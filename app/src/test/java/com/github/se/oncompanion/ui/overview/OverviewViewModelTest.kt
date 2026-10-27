// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.VirtualClock
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class OverviewViewModelTest {

  private val zone = ZoneOffset.UTC

  /** Saturday 3 October 2026, 9:00. */
  private val now = LocalDateTime.of(2026, 10, 3, 9, 0)

  /** A clock the tests can move forward. */
  private class TestClock(var time: LocalDateTime) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = time.toInstant(ZoneOffset.UTC)
  }

  private fun at(time: LocalDateTime): PlanningTiming = PlanningTiming.Timed(time.toInstant(zone))

  private fun appointment(id: String, time: LocalDateTime, place: String? = null) =
      PlanningItem(PlanningSource.Appointment(id), at(time), "Appointment $id", subtitle = place)

  private fun event(id: String, time: LocalDateTime) =
      PlanningItem(PlanningSource.Event(id), at(time), "Event $id", subtitle = "Place $id")

  private fun medication(
      id: String,
      date: LocalDate = now.toLocalDate(),
      frequency: String? = null,
  ) =
      PlanningItem(
          PlanningSource.Medication("prescription", id),
          PlanningTiming.DateOnly(date),
          "Medication $id",
          frequency = frequency,
      )

  private fun planning(vararg items: PlanningItem) =
      FakePlanningRepository().apply { this.items.value = items.toList() }

  private fun titlesOf(vm: OverviewViewModel) = vm.uiState.value.todayEntries.map { it.title }

  private fun statusesOf(vm: OverviewViewModel) =
      vm.uiState.value.todayEntries.associate { it.title to it.status }

  private fun signedIn(user: AuthUser) =
      FakeAuthRepository(onSignIn = { user }).also { runBlocking { it.signInWithGoogle("token") } }

  private fun viewModel(
      auth: AuthRepository = FakeAuthRepository(),
      profiles: FakeUserProfileRepository = FakeUserProfileRepository(),
      planning: PlanningRepository = FakePlanningRepository(),
      clock: Clock = TestClock(now),
  ) = OverviewViewModel(auth, profiles, planning, clock, zone)

  // ----- First name -----

  @Test
  fun signedOut_greetsWithoutAName() {
    assertNull(viewModel().uiState.value.firstName)
  }

  @Test
  fun firstName_comesFromTheProfile() {
    val profiles = FakeUserProfileRepository()
    profiles.seed(UserProfile(uid = "u1", role = Role.PATIENT, firstName = "Alex"))

    val state =
        viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")), profiles)

    assertEquals("Alex", state.uiState.value.firstName)
  }

  @Test
  fun withoutProfile_firstNameComesFromTheGoogleAccount() {
    val vm = viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")))

    assertEquals("Alexandra", vm.uiState.value.firstName)
  }

  @Test
  fun profileError_keepsTheGoogleName_andDoesNotBreakTheDay() {
    val profiles = FakeUserProfileRepository().apply { getProfileError = IllegalStateException() }

    val state =
        viewModel(signedIn(AuthUser(uid = "u1", displayName = "Alexandra Martin")), profiles)
            .uiState
            .value

    assertEquals("Alexandra", state.firstName)
    assertFalse(state.hasError)
  }

  @Test
  fun authFailure_greetsWithoutAName() {
    val brokenAuth =
        object : AuthRepository by FakeAuthRepository() {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase isn't initialized")
        }

    val state = viewModel(auth = brokenAuth).uiState.value

    assertNull(state.firstName)
    assertFalse(state.hasError)
  }

  // ----- Reading the planning data -----

  @Test
  fun emptyPlanning_givesAnEmptyLoadedDay() {
    val state = viewModel().uiState.value

    assertFalse(state.isLoading)
    assertNull(state.nextAppointment)
    assertTrue(state.todayEntries.isEmpty())
    assertEquals(now, state.now)
  }

  @Test
  fun planningIsObserved_fromTodayForAYear_inTheGivenZone() {
    val planning = FakePlanningRepository()
    viewModel(planning = planning)

    val range = planning.ranges.single()
    assertEquals(now.toLocalDate(), range.startDateInclusive)
    assertEquals(now.toLocalDate().plusYears(1), range.endDateExclusive)
    assertEquals(zone, range.zoneId)
  }

  @Test
  fun nextAppointment_monthsAway_isStillFound() {
    // Oncology check-ups are often months apart
    val vm = viewModel(planning = planning(appointment("check-up", now.plusMonths(4))))

    assertEquals(
        NextAppointment("check-up", "Appointment check-up", now.plusMonths(4)),
        vm.uiState.value.nextAppointment,
    )
    assertTrue(vm.uiState.value.todayEntries.isEmpty())
  }

  @Test
  fun stateIsLoading_untilThePlanningEmits() {
    val release = CompletableDeferred<Unit>()
    val slow =
        FakePlanningRepository().apply {
          observation = {
            flow {
              release.await()
              emit(emptyList())
            }
          }
        }
    val vm = viewModel(planning = slow)

    assertTrue(vm.uiState.value.isLoading)

    release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun failingPlanning_givesAnError_andReloadRecovers() {
    val planning = planning(event("yoga", now.withHour(10))).apply { fail = true }
    val vm = viewModel(planning = planning)

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    planning.fail = false
    vm.loadDay()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(listOf("Event yoga"), titlesOf(vm))
  }

  @Test
  fun planningChanges_updateTheState() {
    val planning = FakePlanningRepository()
    val vm = viewModel(planning = planning)

    planning.items.value = listOf(event("yoga", now.withHour(10)))
    ShadowLooper.idleMainLooper()

    assertEquals(listOf("Event yoga"), titlesOf(vm))
  }

  // ----- Next appointment -----

  @Test
  fun nextAppointment_isTheFirstThatHasntStarted_withItsPlace() {
    val vm =
        viewModel(
            planning =
                planning(
                    appointment("started", now.minusMinutes(30)),
                    appointment("later", now.plusDays(3)),
                    appointment("soon", now.plusDays(1), place = "HUG"),
                    event("yoga", now.plusHours(2)),
                )
        )

    assertEquals(
        NextAppointment("soon", "Appointment soon", now.plusDays(1), "HUG"),
        vm.uiState.value.nextAppointment,
    )
  }

  @Test
  fun eventsAndMedications_areNeverTheNextAppointment() {
    val vm = viewModel(planning = planning(event("yoga", now.plusHours(2)), medication("m")))

    assertNull(vm.uiState.value.nextAppointment)
  }

  @Test
  fun nextAppointment_movesOn_onceItStarts() {
    val clock = TestClock(now)
    val vm =
        viewModel(
            planning =
                planning(appointment("a", now.plusHours(1)), appointment("b", now.plusDays(1))),
            clock = clock,
        )
    assertEquals("Appointment a", vm.uiState.value.nextAppointment?.title)

    clock.time = now.plusHours(2)
    vm.refreshNow()

    assertEquals("Appointment b", vm.uiState.value.nextAppointment?.title)
  }

  // ----- Today -----

  @Test
  fun today_keepsOnlyTodaysEntries_untimedFirst_thenByTime() {
    val vm =
        viewModel(
            planning =
                planning(
                    event("evening", now.withHour(18)),
                    appointment("tomorrow", now.plusDays(1)),
                    appointment("morning", now.withHour(10)),
                    medication("today", frequency = "Twice a day"),
                    medication("tomorrow", now.toLocalDate().plusDays(1)),
                )
        )

    val entries = vm.uiState.value.todayEntries
    assertEquals(
        listOf("Medication today", "Appointment morning", "Event evening"),
        entries.map { it.title },
    )
    assertEquals(listOf(null, LocalTime.of(10, 0), LocalTime.of(18, 0)), entries.map { it.time })
    assertEquals(
        listOf(TodayItemKind.MEDICATION, TodayItemKind.APPOINTMENT, TodayItemKind.EVENT),
        entries.map { it.kind },
    )
  }

  @Test
  fun medication_showsItsFrequency_andNoStatus() {
    val vm = viewModel(planning = planning(medication("m", frequency = "Twice a day")))

    val entry = vm.uiState.value.todayEntries.single()
    assertEquals("Twice a day", entry.subtitle)
    // The app never decides whether a medication was taken
    assertNull(entry.status)
  }

  @Test
  fun timedEntries_areDoneNowNextOrLater_withTheClock() {
    val vm =
        viewModel(
            planning =
                planning(
                    event("over", now.withHour(7)),
                    appointment("now", now.minusMinutes(10)),
                    event("next", now.withHour(15)),
                    event("later", now.withHour(18)),
                )
        )

    assertEquals(
        mapOf(
            "Event over" to TodayItemStatus.DONE,
            "Appointment now" to TodayItemStatus.NOW,
            "Event next" to TodayItemStatus.NEXT,
            "Event later" to TodayItemStatus.LATER,
        ),
        statusesOf(vm),
    )
  }

  @Test
  fun anEntry_isNowForAnHour_thenDone() {
    val clock = TestClock(now)
    val vm =
        viewModel(planning = planning(appointment("chemo", now.minusMinutes(10))), clock = clock)
    assertEquals(TodayItemStatus.NOW, statusesOf(vm)["Appointment chemo"])

    clock.time = now.plusMinutes(50) // an hour after it started
    vm.refreshNow()

    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Appointment chemo"])
  }

  @Test
  fun whenEverythingIsOver_nothingIsNext() {
    val vm =
        viewModel(planning = planning(event("a", now.withHour(6)), event("b", now.withHour(7))))

    assertTrue(vm.uiState.value.todayEntries.all { it.status == TodayItemStatus.DONE })
  }

  @Test
  fun refreshNow_updatesTheTime_andTheStatuses() {
    val clock = TestClock(now)
    val vm = viewModel(planning = planning(event("lunch", now.withHour(13))), clock = clock)
    assertEquals(TodayItemStatus.NEXT, statusesOf(vm)["Event lunch"])

    clock.time = now.withHour(20)
    vm.refreshNow()

    assertEquals(now.withHour(20), vm.uiState.value.now)
    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["Event lunch"])
  }

  @Test
  fun refreshNow_reloadsTheDay_onlyWhenTheDateChanges() {
    val clock = TestClock(now)
    val planning = FakePlanningRepository()
    val vm = viewModel(planning = planning, clock = clock)
    assertEquals(1, planning.ranges.size)

    clock.time = now.withHour(23)
    vm.refreshNow()
    assertEquals(1, planning.ranges.size)

    clock.time = now.plusDays(1).withHour(7)
    vm.refreshNow()
    assertEquals(2, planning.ranges.size)
    assertEquals(now.toLocalDate().plusDays(1), planning.ranges.last().startDateInclusive)
    assertEquals(now.plusDays(1).toLocalDate(), vm.uiState.value.now.toLocalDate())
  }

  // ----- Following the date -----

  @Test
  fun followToday_refreshesTheTimeRightAway() = runTest {
    val clock = TestClock(now)
    val vm = viewModel(clock = clock)

    // Time passed while the Overview was in the background
    clock.time = now.withHour(20)
    backgroundScope.launch { vm.followToday() }
    runCurrent()

    assertEquals(now.withHour(20), vm.uiState.value.now)
  }

  @Test
  fun followToday_reloadsTheNewDayAtMidnight() = runTest {
    val lateEvening = now.withHour(23).withMinute(59)
    val planning = FakePlanningRepository()
    val clock = VirtualClock(testScheduler, lateEvening.toInstant(zone), zone)
    val vm = viewModel(planning = planning, clock = clock)
    backgroundScope.launch { vm.followToday() }
    runCurrent()
    assertEquals(1, planning.ranges.size)

    advanceTimeBy(1.minutes)
    runCurrent()

    val tomorrow = now.toLocalDate().plusDays(1)
    assertEquals(tomorrow, vm.uiState.value.now.toLocalDate())
    assertEquals(2, planning.ranges.size)
    assertEquals(tomorrow, planning.ranges.last().startDateInclusive)
  }
}
