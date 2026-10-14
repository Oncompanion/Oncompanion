// Co-authored-by: Codex
package com.github.se.oncompanion.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.hospital.Hospital
import com.github.se.oncompanion.model.hospital.HospitalRepository
import com.github.se.oncompanion.model.hospital.HospitalSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Search feedback independent of the form's manually entered location text. */
sealed interface HospitalFieldUiState {
  /** No search has been submitted for the current text. */
  data object Idle : HospitalFieldUiState

  /** An explicit search is in progress; manual text remains editable. */
  data object Loading : HospitalFieldUiState

  /** Matching hospitals, or an empty list when the search found none. */
  data class Results(val hospitals: List<Hospital>) : HospitalFieldUiState

  /** A recoverable search failure; free text remains valid. */
  data object Error : HospitalFieldUiState
}

/** Owns explicit searches through one injected repository, preserving its existing cache. */
class HospitalFieldViewModel(private val repository: HospitalRepository) : ViewModel() {
  private val state = MutableStateFlow<HospitalFieldUiState>(HospitalFieldUiState.Idle)
  val uiState = state.asStateFlow()
  private var searchJob: Job? = null
  private var generation = 0

  /** Searches submitted public location text; blank input never calls the repository. */
  fun search(text: String) {
    if (text.isBlank() || state.value == HospitalFieldUiState.Loading) return
    val attempt = ++generation
    state.value = HospitalFieldUiState.Loading
    searchJob = viewModelScope.launch {
      try {
        val result = searchResults(text)
        if (attempt == generation) state.value = result
      } catch (cancelled: CancellationException) {
        if (attempt == generation) state.value = HospitalFieldUiState.Idle
        throw cancelled
      }
    }
  }

  private suspend fun searchResults(text: String): HospitalFieldUiState =
      try {
        // Always ask the repository, including offline: it may already have cached this query.
        when (val result = repository.searchHospitals(text)) {
          is HospitalSearchResult.Success -> HospitalFieldUiState.Results(result.hospitals)
          is HospitalSearchResult.Error -> HospitalFieldUiState.Error
        }
      } catch (cancelled: CancellationException) {
        // Lifecycle cancellation must not appear as a recoverable search failure.
        throw cancelled
      } catch (_: Exception) {
        HospitalFieldUiState.Error
      }

  /** Clears obsolete feedback on editing, selection or removal without changing the form's text. */
  fun clearSearch() {
    // Generation also rejects late results from a transport that cannot cancel immediately.
    generation++
    searchJob?.cancel()
    searchJob = null
    state.value = HospitalFieldUiState.Idle
  }
}
