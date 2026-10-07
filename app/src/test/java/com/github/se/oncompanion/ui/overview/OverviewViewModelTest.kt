package com.github.se.oncompanion.ui.overview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.overview.FakeOverviewRepository
import com.github.se.oncompanion.model.overview.NextAppointment
import com.github.se.oncompanion.model.overview.OverviewRepository
import com.github.se.oncompanion.model.overview.TodayItem
import com.github.se.oncompanion.model.overview.TodayItemKind
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

  private val now = LocalDateTime.of(2026, 10, 3, 9, 0)

  private fun item(id: String, hour: Int, isTaken: Boolean = false) =
      TodayItem(
          id = id,
          kind = TodayItemKind.MEDICATION,
          time = now.withHour(hour),
          title = "Item $id",
          isTaken = isTaken,
      )

  private fun event(id: String, start: LocalDateTime, end: LocalDateTime? = null) =
      TodayItem(id = id, kind = TodayItemKind.EVENT, time = start, endTime = end, title = id)

  private fun statusesOf(vm: OverviewViewModel) =
      vm.uiState.value.todayEntries.associate { it.item.id to it.status }

  private fun signedIn(user: AuthUser) =
      FakeAuthRepository(onSignIn = { user }).also { runBlocking { it.signInWithGoogle("token") } }

  private fun viewModel(
      auth: AuthRepository = FakeAuthRepository(),
      profiles: FakeUserProfileRepository = FakeUserProfileRepository(),
      overview: OverviewRepository = FakeOverviewRepository(),
  ) = OverviewViewModel(auth, { profiles }, overview, clock = { now })

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

  // ----- The day -----

  @Test
  fun emptyRepository_givesAnEmptyLoadedDay() {
    val state = viewModel().uiState.value

    assertFalse(state.isLoading)
    assertNull(state.nextAppointment)
    assertTrue(state.todayEntries.isEmpty())
    assertEquals(now, state.now)
  }

  @Test
  fun nextAppointment_isShown() {
    val appointment = NextAppointment(title = "Check-up", dateTime = now.plusDays(1))

    val state = viewModel(overview = FakeOverviewRepository(appointment)).uiState.value

    assertEquals(appointment, state.nextAppointment)
  }

  @Test
  fun todayItems_areSorted_andTheFirstNotDoneIsNext() {
    val items = listOf(item("late", 18), item("taken", 8, isTaken = true), item("soon", 13))

    val entries =
        viewModel(overview = FakeOverviewRepository(todayItems = items)).uiState.value.todayEntries

    assertEquals(listOf("taken", "soon", "late"), entries.map { it.item.id })
    assertEquals(
        listOf(TodayItemStatus.DONE, TodayItemStatus.NEXT, TodayItemStatus.LATER),
        entries.map { it.status },
    )
  }

  @Test
  fun whenEverythingIsDone_nothingIsNext() {
    val items = listOf(item("a", 8, isTaken = true), item("b", 9, isTaken = true))

    val entries =
        viewModel(overview = FakeOverviewRepository(todayItems = items)).uiState.value.todayEntries

    assertTrue(entries.all { it.status == TodayItemStatus.DONE })
  }

  @Test
  fun newItems_updateTheState() {
    val repository = FakeOverviewRepository()
    val vm = viewModel(overview = repository)

    repository.todayItems.value = listOf(item("a", 10))
    ShadowLooper.idleMainLooper()

    assertEquals(listOf("a"), vm.uiState.value.todayEntries.map { it.item.id })
  }

  @Test
  fun stateIsLoading_untilTheRepositoryEmits() {
    val release = CompletableDeferred<Unit>()
    val slow =
        object : OverviewRepository {
          override fun observeNextAppointment(): Flow<NextAppointment?> = flow {
            release.await()
            emit(null)
          }

          override fun observeTodayItems(): Flow<List<TodayItem>> = flowOf(emptyList())
        }
    val vm = viewModel(overview = slow)

    assertTrue(vm.uiState.value.isLoading)

    release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun failingRepository_givesAnError_andReloadRecovers() {
    var observations = 0
    val failingOnce =
        object : OverviewRepository {
          override fun observeNextAppointment(): Flow<NextAppointment?> = flow {
            if (observations++ == 0) throw IllegalStateException("no network")
            emit(null)
          }

          override fun observeTodayItems(): Flow<List<TodayItem>> = flowOf(listOf(item("a", 10)))
        }
    val vm = viewModel(overview = failingOnce)

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)

    vm.loadDay()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(1, vm.uiState.value.todayEntries.size)
  }

  // ----- Status decided with the clock -----

  @Test
  fun pastMedicationNotTaken_isNotTaken_andTheNextOneIsNext() {
    val items = listOf(item("morning", 8), item("lunch", 13))

    val vm = viewModel(overview = FakeOverviewRepository(todayItems = items))

    assertEquals(
        mapOf("morning" to TodayItemStatus.NOT_TAKEN, "lunch" to TodayItemStatus.NEXT),
        statusesOf(vm),
    )
  }

  @Test
  fun eventsAndAppointments_areNowWhileHappening_andDoneOnceOver() {
    val items =
        listOf(
            event("ended", now.withHour(7), end = now.withHour(8)),
            event("no-end-over", now.withHour(7).withMinute(30)),
            event("in-progress", now.withHour(8), end = now.withHour(10)),
            event("later", now.withHour(15)),
            event("evening", now.withHour(18)),
        )

    val vm = viewModel(overview = FakeOverviewRepository(todayItems = items))

    assertEquals(
        mapOf(
            "ended" to TodayItemStatus.DONE,
            "no-end-over" to TodayItemStatus.DONE,
            "in-progress" to TodayItemStatus.NOW,
            "later" to TodayItemStatus.NEXT,
            "evening" to TodayItemStatus.LATER,
        ),
        statusesOf(vm),
    )
  }

  @Test
  fun appointmentWithoutEnd_isNowForAnHour_thenDone() {
    var time = now // 9:00
    val chemo =
        TodayItem(
            id = "chemo",
            kind = TodayItemKind.APPOINTMENT,
            time = now.minusMinutes(10),
            title = "Chemotherapy session",
        )
    val vm =
        OverviewViewModel(
            FakeAuthRepository(),
            { FakeUserProfileRepository() },
            FakeOverviewRepository(todayItems = listOf(chemo, item("lunch", 13))),
            clock = { time },
        )

    // Started 10 minutes ago: still happening, and the medication after it is next
    assertEquals(
        mapOf("chemo" to TodayItemStatus.NOW, "lunch" to TodayItemStatus.NEXT),
        statusesOf(vm),
    )

    time = now.plusMinutes(50) // an hour after it started
    vm.refreshNow()

    assertEquals(TodayItemStatus.DONE, statusesOf(vm)["chemo"])
  }

  @Test
  fun refreshNow_updatesTheTime_andTheStatuses() {
    var time = now
    val vm =
        OverviewViewModel(
            FakeAuthRepository(),
            { FakeUserProfileRepository() },
            FakeOverviewRepository(todayItems = listOf(item("lunch", 13))),
            clock = { time },
        )
    assertEquals(TodayItemStatus.NEXT, statusesOf(vm)["lunch"])

    time = now.withHour(20)
    vm.refreshNow()

    assertEquals(now.withHour(20), vm.uiState.value.now)
    assertEquals(TodayItemStatus.NOT_TAKEN, statusesOf(vm)["lunch"])
  }

  @Test
  fun refreshNow_reloadsTheDay_onlyWhenTheDateChanges() {
    var time = now
    var observations = 0
    val counting =
        object : OverviewRepository {
          override fun observeNextAppointment(): Flow<NextAppointment?> = flowOf(null)

          override fun observeTodayItems(): Flow<List<TodayItem>> = flow {
            observations++
            emit(emptyList())
          }
        }
    val vm =
        OverviewViewModel(
            FakeAuthRepository(),
            { FakeUserProfileRepository() },
            counting,
            clock = { time },
        )
    assertEquals(1, observations)

    time = now.withHour(23)
    vm.refreshNow()
    assertEquals(1, observations)

    time = now.plusDays(1).withHour(7)
    vm.refreshNow()
    assertEquals(2, observations)
    assertEquals(now.plusDays(1).toLocalDate(), vm.uiState.value.now.toLocalDate())
  }
}
