// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Shared controllable device preferences for screen, ViewModel and root-theme tests. */
class FakeSettingsRepository(initial: DeviceSettings = DeviceSettings()) : SettingsRepository {
  val settings = MutableStateFlow(initial)
  val writes = mutableListOf<AppearancePreference>()
  var readFailure: Exception? = null
  var writeFailure: Exception? = null

  override fun observeSettings(): Flow<DeviceSettings> = flow {
    readFailure?.let { throw it }
    emitAll(settings)
  }

  override suspend fun setAppearance(appearance: AppearancePreference) {
    writes += appearance
    writeFailure?.let { throw it }
    settings.value = DeviceSettings(appearance)
  }
}
