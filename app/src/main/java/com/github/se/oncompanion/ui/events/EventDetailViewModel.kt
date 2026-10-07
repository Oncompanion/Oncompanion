package com.github.se.oncompanion.ui.events

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the event detail screen shows.
 *
 * @property event the event, once loaded
 * @property isLoading true until the event is loaded
 * @property notFound true if no event has this id
 * @property hasError true if the event couldn't be loaded
 */
data class EventDetailUiState(
    val event: Event? = null,
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val hasError: Boolean = false,
)

/** Loads one of the Ligue's events for its detail screen. */
class EventDetailViewModel(
    private val eventId: String,
    private val repository: EventRepository = FakeEventRepository(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(EventDetailUiState())
  val uiState: StateFlow<EventDetailUiState> = _uiState.asStateFlow()

  init {
    loadEvent()
  }

  /** Loads (or, after an error, reloads) the event. */
  fun loadEvent() {
    _uiState.value = EventDetailUiState()
    viewModelScope.launch {
      _uiState.value =
          try {
            val event = repository.getEvent(eventId)
            EventDetailUiState(event = event, isLoading = false, notFound = event == null)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Log.e(TAG, "Failed to load event $eventId", e)
            EventDetailUiState(isLoading = false, hasError = true)
          }
    }
  }

  private companion object {
    const val TAG = "EventDetailViewModel"
  }
}
