// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import kotlinx.coroutines.flow.Flow

/** Stores device preferences offline without tying them to an account. */
interface SettingsRepository {
  /** Observes persisted preferences, with device defaults for unset values. */
  fun observeSettings(): Flow<DeviceSettings>

  /** Saves an appearance override; returns only after the local write succeeds. */
  suspend fun setAppearance(appearance: AppearancePreference)
}
