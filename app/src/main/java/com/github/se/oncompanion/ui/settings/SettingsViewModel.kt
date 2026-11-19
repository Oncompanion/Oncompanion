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

/**
 * Mutually exclusive phases of a settings write; failures keep the confirmed selection editable.
 */
enum class SettingsSavePhase {
  /** No write is pending. */
  IDLE,
  /** A local write is pending and selection is disabled. */
  SAVING,
  /** The last write failed and the user can try again. */
  FAILED,
}

/** The confirmed device preference and any pending operation or recoverable failure. */
data class SettingsUiState(
    val settings: DeviceSettings? = null,
    val readFailed: Boolean = false,
    val savePhase: SettingsSavePhase = SettingsSavePhase.IDLE,
) {
  /** Whether selection must wait for the pending local write. */
  val saving: Boolean
    get() = savePhase == SettingsSavePhase.SAVING

  /** Whether the last write needs a recoverable error message. */
  val saveFailed: Boolean
    get() = savePhase == SettingsSavePhase.FAILED
}

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
    state.value = current.copy(savePhase = SettingsSavePhase.SAVING)
    viewModelScope.launch {
      try {
        repository.setAppearance(appearance)
        state.value =
            state.value.copy(
                settings = state.value.settings?.copy(appearance = appearance),
                savePhase = SettingsSavePhase.IDLE,
            )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        state.value = state.value.copy(savePhase = SettingsSavePhase.FAILED)
      } finally {
        // Cancellation unlocks the rows without overwriting a completed or failed operation.
        if (state.value.saving) state.value = state.value.copy(savePhase = SettingsSavePhase.IDLE)
      }
    }
  }
}
