// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persists preferences in the single application-owned DataStore. Read/write errors propagate. */
class SettingsRepositoryDataStore(storage: Lazy<DataStore<Preferences>>) : SettingsRepository {
  private val store by storage

  override fun observeSettings(): Flow<DeviceSettings> =
      store.data.map { preferences ->
        // Unknown values from another app version retain the safe device default.
        val appearance =
            AppearancePreference.entries.firstOrNull { it.name == preferences[APPEARANCE] }
                ?: AppearancePreference.FOLLOW_DEVICE
        DeviceSettings(appearance)
      }

  override suspend fun setAppearance(appearance: AppearancePreference) {
    store.edit { it[APPEARANCE] = appearance.name }
  }

  private companion object {
    val APPEARANCE = stringPreferencesKey("appearance")
  }
}
