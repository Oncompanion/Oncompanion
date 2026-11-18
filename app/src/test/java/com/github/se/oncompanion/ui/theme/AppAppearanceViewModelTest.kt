// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.theme

import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.settings.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Verifies startup readiness, fallback and recovery without changing stored preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppAppearanceViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val models = mutableListOf<AppAppearanceViewModel>()

  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun teardown() {
    models.forEach { it.viewModelScope.cancel() }
    Dispatchers.resetMain()
  }

  private fun model(repo: FakeSettingsRepository) =
      AppAppearanceViewModel(repo).also { models += it }

  @Test
  fun startsUnresolvedThenObservesEverySavedPreference() = runTest {
    val repo = FakeSettingsRepository()
    val vm = model(repo)
    assertNull(vm.uiState.value.appearance)
    runCurrent()
    for (choice in AppearancePreference.entries) {
      repo.settings.value = DeviceSettings(choice)
      runCurrent()
      assertEquals(choice, vm.uiState.value.appearance)
    }
    assertTrue(repo.writes.isEmpty())
  }

  @Test
  fun readFailureReleasesStartupAndRetriesWithoutOverwritingTheStore() = runTest {
    val repo =
        FakeSettingsRepository(DeviceSettings(AppearancePreference.DARK)).apply {
          readFailure = IOException("read")
        }
    val vm = model(repo)
    runCurrent()
    assertEquals(AppearancePreference.FOLLOW_DEVICE, vm.uiState.value.appearance)
    repo.readFailure = null
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(AppearancePreference.DARK, vm.uiState.value.appearance)
    assertTrue(repo.writes.isEmpty())
  }

  @Test
  fun unexpectedReadFailureUsesDefaultAndCancellationDoesNotResolveStartup() = runTest {
    val broken =
        model(FakeSettingsRepository().apply { readFailure = IllegalStateException("broken") })
    val cancelled =
        model(FakeSettingsRepository().apply { readFailure = CancellationException("cancel") })
    runCurrent()
    assertEquals(AppearancePreference.FOLLOW_DEVICE, broken.uiState.value.appearance)
    assertNull(cancelled.uiState.value.appearance)
  }

  @Test
  fun laterIoFailuresRetainDarkUntilTheStoreRecovers() = runTest {
    val repo = FakeSettingsRepository(DeviceSettings(AppearancePreference.DARK))
    val vm = model(repo)
    runCurrent()
    repo.observationFailure.value = IOException("disk")
    runCurrent()
    repeat(3) {
      advanceTimeBy(1_000)
      runCurrent()
      assertEquals(AppearancePreference.DARK, vm.uiState.value.appearance)
    }
    repo.observationFailure.value = null
    repo.settings.value = DeviceSettings(AppearancePreference.LIGHT)
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(AppearancePreference.LIGHT, vm.uiState.value.appearance)
    assertTrue(repo.writes.isEmpty())
  }

  @Test
  fun laterUnexpectedFailureRetainsTheLastReadChoice() = runTest {
    val repo = FakeSettingsRepository(DeviceSettings(AppearancePreference.DARK))
    val vm = model(repo)
    runCurrent()
    repo.observationFailure.value = IllegalStateException("broken")
    runCurrent()
    assertEquals(AppearancePreference.DARK, vm.uiState.value.appearance)
    assertTrue(repo.writes.isEmpty())
  }

  @Test
  fun appearanceOverridesResolveForBothSystemThemes() {
    for (systemDark in listOf(false, true)) {
      assertEquals(systemDark, AppearancePreference.FOLLOW_DEVICE.usesDarkTheme(systemDark))
      assertFalse(AppearancePreference.LIGHT.usesDarkTheme(systemDark))
      assertTrue(AppearancePreference.DARK.usesDarkTheme(systemDark))
    }
  }
}
