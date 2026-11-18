// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.settings.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The confirmed device preference and any pending operation or recoverable failure. */
data class SettingsUiState(
    val settings: DeviceSettings? = null,
    val readFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/** Observes and saves device preferences without depending on authentication. */
class SettingsViewModel(
    private val repository: SettingsRepository = SettingsRepositoryProvider.repository
) : ViewModel() {
  private val state = MutableStateFlow(SettingsUiState())
  /** The last confirmed preference remains selected if a write fails. */
  val uiState: StateFlow<SettingsUiState> = state.asStateFlow()
  private var observation: Job? = null

  init {
    retry()
  }

  /** Restarts a failed read while retaining any previously confirmed preference. */
  fun retry() {
    observation?.cancel()
    state.value = state.value.copy(readFailed = false)
    observation = viewModelScope.launch {
      try {
        repository.observeSettings().collect {
          state.value = state.value.copy(settings = it, readFailed = false)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        state.value = state.value.copy(readFailed = true)
      }
    }
  }

  /** Saves one explicit selection, leaving its confirmed predecessor visible until success. */
  fun selectAppearance(appearance: AppearancePreference) {
    val current = state.value
    if (current.settings == null || current.saving || current.settings.appearance == appearance)
        return
    state.value = current.copy(saving = true, saveFailed = false)
    viewModelScope.launch {
      try {
        repository.setAppearance(appearance)
        state.value = state.value.copy(settings = DeviceSettings(appearance))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        state.value = state.value.copy(saveFailed = true)
      } finally {
        state.value = state.value.copy(saving = false)
      }
    }
  }
}
