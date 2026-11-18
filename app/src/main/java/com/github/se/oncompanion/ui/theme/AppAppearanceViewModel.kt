// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.settings.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** The device appearance is unresolved only until the first local read completes. */
data class AppAppearanceUiState(val appearance: AppearancePreference? = null)

/** Restores the device appearance before startup and observes changes across account sessions. */
class AppAppearanceViewModel(
    private val repository: SettingsRepository = SettingsRepositoryProvider.repository
) : ViewModel() {
  private val state = MutableStateFlow(AppAppearanceUiState())
  /** Null only until the first read; a failed read releases startup using the device default. */
  val uiState: StateFlow<AppAppearanceUiState> = state.asStateFlow()

  init {
    viewModelScope.launch {
      repository
          .observeSettings()
          .retryWhen { cause, _ ->
            if (cause is IOException) {
              // A disk failure must not hold the splash indefinitely or overwrite saved
              // preferences.
              state.value = AppAppearanceUiState(AppearancePreference.FOLLOW_DEVICE)
              delay(1_000)
              true
            } else false
          }
          .catch { cause ->
            if (cause is CancellationException) throw cause
            state.value = AppAppearanceUiState(AppearancePreference.FOLLOW_DEVICE)
          }
          .collect { state.value = AppAppearanceUiState(it.appearance) }
    }
  }
}

/** Resolves an override while keeping Follow device responsive to configuration changes. */
internal fun AppearancePreference.usesDarkTheme(systemDark: Boolean): Boolean =
    when (this) {
      AppearancePreference.FOLLOW_DEVICE -> systemDark
      AppearancePreference.LIGHT -> false
      AppearancePreference.DARK -> true
    }
