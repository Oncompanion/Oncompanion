package com.github.se.oncompanion.ui.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class EventsViewModelTest {

  private fun event(id: String) =
      Event(
          id = id,
          title = "Event $id",
          place = "Place $id",
          startDateTime = LocalDateTime.of(2026, 10, 20, 12, 0),
          summary = "Summary $id",
          description = "Description $id",
      )

  /** Emits whatever list is in [events], like a live Firestore query. */
  private class LiveRepository(initial: List<Event>) : EventRepository {
    val events = MutableStateFlow(initial)

    override fun getUpcomingEvents(): Flow<List<Event>> = events

    override suspend fun getEvent(id: String): Event? = null
  }

  /** Emits its events only once [release] is completed, to observe the loading state. */
  private class SuspendingRepository(private val events: List<Event>) : EventRepository {
    val release = CompletableDeferred<Unit>()

    override fun getUpcomingEvents(): Flow<List<Event>> = flow {
      release.await()
      emit(events)
    }

    override suspend fun getEvent(id: String): Event? = null
  }

  /** Fails the first time it is observed, then emits [events]. */
  private class FailingOnceRepository(private val events: List<Event>) : EventRepository {
    var observations = 0

    override fun getUpcomingEvents(): Flow<List<Event>> = flow {
      if (observations++ == 0) throw IllegalStateException("no network")
      emit(events)
    }

    override suspend fun getEvent(id: String): Event? = null
  }

  @Test
  fun defaultRepository_showsTheSampleEvents() = runBlocking {
    val state = EventsViewModel().uiState.value

    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertEquals(FakeEventRepository().getUpcomingEvents().first(), state.events)
  }

  @Test
  fun emptyRepository_givesAnEmptyLoadedState() {
    val state = EventsViewModel(FakeEventRepository(emptyList())).uiState.value

    assertTrue(state.events.isEmpty())
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
  }

  @Test
  fun stateIsLoading_untilTheRepositoryEmits() {
    val repository = SuspendingRepository(listOf(event("a")))
    val viewModel = EventsViewModel(repository)

    assertTrue(viewModel.uiState.value.isLoading)

    repository.release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertFalse(viewModel.uiState.value.isLoading)
    assertEquals(1, viewModel.uiState.value.events.size)
  }

  @Test
  fun newListFromTheRepository_updatesTheState() {
    val repository = LiveRepository(listOf(event("a")))
    val viewModel = EventsViewModel(repository)

    repository.events.value = listOf(event("a"), event("b"))
    ShadowLooper.idleMainLooper()

    assertEquals(listOf("a", "b"), viewModel.uiState.value.events.map { it.id })
  }

  @Test
  fun failingRepository_givesAnErrorState_andReloadRecovers() {
    val events = listOf(event("a"))
    val viewModel = EventsViewModel(FailingOnceRepository(events))

    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isLoading)

    viewModel.loadEvents()

    assertFalse(viewModel.uiState.value.hasError)
    assertEquals(events, viewModel.uiState.value.events)
  }

  @Test
  fun errorAfterAFirstList_keepsTheEvents() {
    val events = listOf(event("a"))
    val failingLater =
        object : EventRepository {
          override fun getUpcomingEvents(): Flow<List<Event>> = flow {
            emit(events)
            throw IllegalStateException("connection lost")
          }

          override suspend fun getEvent(id: String): Event? = null
        }

    val state = EventsViewModel(failingLater).uiState.value

    assertTrue(state.hasError)
    assertFalse(state.isLoading)
    assertEquals(events, state.events)
  }
}
