// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "device_settings")

/** Shares one device store while allowing tests to replace the repository. */
object SettingsRepositoryProvider {
  @Volatile private var applicationContext: Context? = null

  /** The repository captured by ViewModels; installing the context never replaces a test fake. */
  @Volatile
  var repository: SettingsRepository =
      SettingsRepositoryDataStore(
          lazy {
            checkNotNull(applicationContext) { "SettingsRepositoryProvider.install must run first" }
                .settingsDataStore
          }
      )

  /** Installs only the application context before settings are first read. */
  fun install(context: Context) {
    applicationContext = context.applicationContext
  }
}
