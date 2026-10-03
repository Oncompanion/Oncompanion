package com.github.se.oncompanion.ui.events

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * What the Events tab shows.
 *
 * @property events the upcoming events, soonest first
 * @property isLoading true until the first list arrives
 * @property hasError true if the events couldn't be loaded
 */
data class EventsUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
)

/** Observes the Ligue's upcoming events for the Events tab. */
class EventsViewModel(private val repository: EventRepository = FakeEventRepository()) :
    ViewModel() {

  private val _uiState = MutableStateFlow(EventsUiState())
  val uiState: StateFlow<EventsUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    loadEvents()
  }

  /** Starts (or, after an error, restarts) observing the upcoming events. */
  fun loadEvents() {
    observation?.cancel()
    _uiState.value = _uiState.value.copy(isLoading = true, hasError = false)
    observation = viewModelScope.launch {
      repository
          .getUpcomingEvents()
          .catch { e ->
            Log.e(TAG, "Failed to load events", e)
            _uiState.value = EventsUiState(isLoading = false, hasError = true)
          }
          .collect { events -> _uiState.value = EventsUiState(events, isLoading = false) }
    }
  }

  private companion object {
    const val TAG = "EventsViewModel"
  }
}
