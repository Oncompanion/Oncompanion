package com.github.se.oncompanion.ui.events

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class EventDetailViewModelTest {

  private val yoga =
      Event(
          id = "yoga",
          title = "Gentle yoga for patients",
          place = "Wellness center, Building B",
          startDateTime = LocalDateTime.of(2026, 10, 10, 10, 0),
          summary = "Summary",
          description = "Description",
      )

  /** Returns [event] from getEvent only once [release] is completed. */
  private class SlowRepository(private val event: Event) : EventRepository {
    val release = CompletableDeferred<Unit>()

    override fun getUpcomingEvents(): Flow<List<Event>> = flowOf(listOf(event))

    override suspend fun getEvent(id: String): Event? {
      release.await()
      return event.takeIf { it.id == id }
    }
  }

  /** Fails the first time getEvent is called, then returns [event]. */
  private class FailingOnceRepository(private val event: Event) : EventRepository {
    var calls = 0

    override fun getUpcomingEvents(): Flow<List<Event>> = flowOf(listOf(event))

    override suspend fun getEvent(id: String): Event? {
      if (calls++ == 0) throw IllegalStateException("no network")
      return event
    }
  }

  @Test
  fun existingEvent_isLoaded() {
    val state = EventDetailViewModel("yoga", FakeEventRepository(listOf(yoga))).uiState.value

    assertEquals(yoga, state.event)
    assertFalse(state.isLoading)
    assertFalse(state.notFound)
    assertFalse(state.hasError)
  }

  @Test
  fun unknownEvent_isNotFound() {
    val state = EventDetailViewModel("missing", FakeEventRepository(listOf(yoga))).uiState.value

    assertNull(state.event)
    assertTrue(state.notFound)
    assertFalse(state.isLoading)
  }

  @Test
  fun defaultRepository_findsTheSampleEvents() {
    assertEquals("gentle-yoga", EventDetailViewModel("gentle-yoga").uiState.value.event?.id)
  }

  @Test
  fun stateIsLoading_untilTheEventArrives() {
    val repository = SlowRepository(yoga)
    val viewModel = EventDetailViewModel("yoga", repository)

    assertTrue(viewModel.uiState.value.isLoading)

    repository.release.complete(Unit)
    ShadowLooper.idleMainLooper()

    assertEquals(yoga, viewModel.uiState.value.event)
  }

  @Test
  fun failure_givesAnError_andReloadRecovers() {
    val viewModel = EventDetailViewModel("yoga", FailingOnceRepository(yoga))

    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isLoading)

    viewModel.loadEvent()

    assertFalse(viewModel.uiState.value.hasError)
    assertEquals(yoga, viewModel.uiState.value.event)
  }
}
