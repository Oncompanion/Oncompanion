package com.github.se.oncompanion.ui.symptom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class SymptomJournalViewModelTest {

  /** The time the ViewModel sees; starts on Monday 5 October 2026, 12:00 in UTC. */
  private var current: Clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC)

  /** Follows [current], so a test can move the time after creating the ViewModel. */
  private val clock =
      object : Clock() {
        override fun getZone(): ZoneId = current.zone

        override fun withZone(zone: ZoneId): Clock = current.withZone(zone)

        override fun instant(): Instant = current.instant()
      }

  /** Uses the same time as the ViewModel, as it rejects symptoms dated after it. */
  private val repository = FakeSymptomRepository(clock = { current.instant() })

  /** The IDs of each day of [state], by date. */
  private fun idsByDay(state: SymptomJournalUiState) =
      state.days.map { day -> day.date to day.entries.map { it.entry.id } }

  private fun entry(id: String, occurredAt: String) =
      SymptomEntry(
          id = id,
          type = SymptomType.FATIGUE,
          intensity = 5,
          occurredAt = Instant.parse(occurredAt),
      )

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(auth: AuthRepository = signedIn()) =
      SymptomJournalViewModel(auth, repository, clock)

  @Test
  fun emptyJournal_isLoadedWithoutError() {
    val state = viewModel().uiState.value

    assertEquals(emptyList<JournalDay>(), state.days)
    assertEquals(LocalDate.of(2026, 10, 5), state.today)
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
  }

  @Test
  fun entries_areTheSignedInUsersOnes_groupedByDay_newestFirst() {
    repository.seed(
        "alice",
        entry("monMorning", "2026-10-05T08:00:00Z"),
        entry("sunEvening", "2026-10-04T21:30:00Z"),
        entry("monNoon", "2026-10-05T12:00:00Z"),
        entry("thu", "2026-10-01T09:05:00Z"),
        entry("sunMorning", "2026-10-04T07:00:00Z"),
    )
    repository.seed("bob", entry("bob", "2026-10-05T10:00:00Z"))

    assertEquals(
        listOf(
            LocalDate.of(2026, 10, 5) to listOf("monNoon", "monMorning"),
            LocalDate.of(2026, 10, 4) to listOf("sunEvening", "sunMorning"),
            LocalDate.of(2026, 10, 1) to listOf("thu"),
        ),
        idsByDay(viewModel().uiState.value),
    )
  }

  @Test
  fun eachEntry_hasItsLocalTime() {
    val entry = entry("s1", "2026-10-04T21:30:00Z")
    repository.seed("alice", entry)

    val day = viewModel().uiState.value.days.single()

    assertEquals(
        JournalDay(LocalDate.of(2026, 10, 4), listOf(JournalEntry(entry, LocalTime.of(21, 30)))),
        day,
    )
  }

  @Test
  fun days_areCountedInTheClocksTimeZone() {
    // 23:30 UTC on Sunday is already Monday 01:30 in Zurich (summer time)
    current = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneId.of("Europe/Zurich"))
    repository.seed("alice", entry("late", "2026-10-04T23:30:00Z"))

    val day = viewModel().uiState.value.days.single()

    assertEquals(LocalDate.of(2026, 10, 5), day.date)
    assertEquals(LocalTime.of(1, 30), day.entries.single().time)
  }

  @Test
  fun refreshToday_followsTheClock() {
    val vm = viewModel()

    current = Clock.fixed(Instant.parse("2026-10-06T00:05:00Z"), ZoneOffset.UTC)
    vm.refreshToday()

    assertEquals(LocalDate.of(2026, 10, 6), vm.uiState.value.today)
  }

  @Test
  fun newEntry_appearsAtTheTop() = runBlocking {
    repository.seed("alice", entry("old", "2026-10-01T08:00:00Z"))
    val vm = viewModel()

    val id = repository.addSymptom("alice", entry("", "2026-10-05T08:00:00Z"))

    assertEquals(
        listOf(
            LocalDate.of(2026, 10, 5) to listOf(id),
            LocalDate.of(2026, 10, 1) to listOf("old"),
        ),
        idsByDay(vm.uiState.value),
    )
  }

  @Test
  fun loadError_showsTheError() {
    repository.observeError = IllegalStateException("no cache")

    val state = viewModel().uiState.value

    assertTrue(state.hasError)
    assertFalse(state.isLoading)
  }

  @Test
  fun retryAfterError_loadsTheJournal() {
    repository.observeError = IllegalStateException("no cache")
    repository.seed("alice", entry("s1", "2026-10-05T08:00:00Z"))
    val vm = viewModel()

    repository.observeError = null
    vm.load()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(listOf(LocalDate.of(2026, 10, 5) to listOf("s1")), idsByDay(vm.uiState.value))
  }

  @Test
  fun signedOut_showsAnError() {
    val state = viewModel(FakeAuthRepository()).uiState.value

    assertTrue(state.hasError)
    assertFalse(state.isLoading)
  }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() {
    // Like the app's NavHost does: without Firebase, the journal shows the error state
    assertTrue(SymptomJournalViewModel().uiState.value.hasError)
  }
}
