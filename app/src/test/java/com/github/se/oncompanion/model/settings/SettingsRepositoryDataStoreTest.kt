// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Checks local persistence and defaults without Firebase or network access. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryDataStoreTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun defaultsAndAllChoicesSurviveReopeningTheStore() = runTest {
    val file = temporary.newFolder().resolve("settings.preferences_pb")
    for (choice in AppearancePreference.entries) {
      val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
      val store = PreferenceDataStoreFactory.create(scope = scope) { file }
      val repo = SettingsRepositoryDataStore(lazyOf(store))
      if (!file.exists()) assertEquals(DeviceSettings(), repo.observeSettings().first())
      repo.setAppearance(choice)
      assertEquals(choice, repo.observeSettings().first().appearance)
      scope.cancel()
      scope.coroutineContext.job.join()
      val reopenedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
      val reopened =
          SettingsRepositoryDataStore(
              lazyOf(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
          )
      assertEquals(choice, reopened.observeSettings().first().appearance)
      reopenedScope.cancel()
      reopenedScope.coroutineContext.job.join()
    }
  }

  @Test
  fun unknownStoredValueFallsBackWithoutDeletingOtherPreferences() = runTest {
    val file = temporary.newFolder().resolve("settings.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    store.edit {
      it[stringPreferencesKey("appearance")] = "future"
      it[stringPreferencesKey("other")] = "kept"
    }
    val repo = SettingsRepositoryDataStore(lazyOf(store))
    assertEquals(DeviceSettings(), repo.observeSettings().first())
    repo.setAppearance(AppearancePreference.DARK)
    assertEquals("kept", store.data.first()[stringPreferencesKey("other")])
  }

  @Test
  fun successfulWritesAreObserved() = runTest {
    val file = temporary.newFolder().resolve("settings.preferences_pb")
    val repo =
        SettingsRepositoryDataStore(
            lazyOf(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        )
    val observed = mutableListOf<DeviceSettings>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      repo.observeSettings().toList(observed)
    }
    repo.observeSettings().first()
    repo.setAppearance(AppearancePreference.LIGHT)
    runCurrent()
    assertEquals(DeviceSettings(AppearancePreference.LIGHT), observed.last())
  }

  @Test
  fun storageFailuresAndCancellationAreNotHidden() = runTest {
    for (failure in listOf(IOException("disk"), CancellationException("cancelled"))) {
      val store =
          object :
              androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data: Flow<androidx.datastore.preferences.core.Preferences> = flow {
              throw failure
            }

            override suspend fun updateData(
                transform:
                    suspend (
                        androidx.datastore.preferences.core.Preferences
                    ) -> androidx.datastore.preferences.core.Preferences
            ): androidx.datastore.preferences.core.Preferences = throw failure
          }
      val repo = SettingsRepositoryDataStore(lazyOf(store))
      try {
        repo.observeSettings().first()
        fail("Expected read failure")
      } catch (actual: Exception) {
        assertSame(failure, actual)
      }
      try {
        repo.setAppearance(AppearancePreference.DARK)
        fail("Expected write failure")
      } catch (actual: Exception) {
        assertSame(failure, actual)
      }
    }
  }
}
