// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.settings

import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.settings.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

/** Exercises persisted choices and recoverable errors with virtual coroutine time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val models = mutableListOf<SettingsViewModel>()

  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun teardown() {
    models.forEach { it.viewModelScope.cancel() }
    Dispatchers.resetMain()
  }

  private fun model(repo: FakeSettingsRepository) = SettingsViewModel(repo).also { models += it }

  @Test
  fun loadsDefaultsAndTracksExternalChanges() = runTest {
    val repo = FakeSettingsRepository()
    val vm = model(repo)
    assertNull(vm.uiState.value.settings)
    vm.selectAppearance(AppearancePreference.DARK)
    assertTrue(repo.writes.isEmpty())
    runCurrent()
    assertEquals(DeviceSettings(), vm.uiState.value.settings)
    repo.settings.value = DeviceSettings(AppearancePreference.DARK)
    runCurrent()
    assertEquals(repo.settings.value, vm.uiState.value.settings)
  }

  @Test
  fun savesEachChoiceAndIgnoresDuplicatesAndOverlappingRequests() = runTest {
    val repo = FakeSettingsRepository()
    val vm = model(repo)
    runCurrent()
    vm.selectAppearance(AppearancePreference.FOLLOW_DEVICE)
    assertTrue(repo.writes.isEmpty())
    for (choice in
        listOf(
            AppearancePreference.LIGHT,
            AppearancePreference.DARK,
            AppearancePreference.FOLLOW_DEVICE,
        )) {
      vm.selectAppearance(choice)
      assertTrue(vm.uiState.value.saving)
      vm.selectAppearance(choice)
      runCurrent()
      assertEquals(choice, vm.uiState.value.settings!!.appearance)
      assertFalse(vm.uiState.value.saving)
    }
    assertEquals(3, repo.writes.size)
  }

  @Test
  fun failedWriteKeepsConfirmedValueAndCanBeRetried() = runTest {
    val repo = FakeSettingsRepository()
    val vm = model(repo)
    runCurrent()
    repo.writeFailure = IOException("disk")
    vm.selectAppearance(AppearancePreference.DARK)
    runCurrent()
    assertEquals(DeviceSettings(), vm.uiState.value.settings)
    assertTrue(vm.uiState.value.saveFailed)
    assertFalse(vm.uiState.value.saving)
    repo.writeFailure = null
    vm.selectAppearance(AppearancePreference.DARK)
    runCurrent()
    assertFalse(vm.uiState.value.saveFailed)
    assertEquals(AppearancePreference.DARK, vm.uiState.value.settings!!.appearance)
  }

  @Test
  fun failedReadCanBeRetried() = runTest {
    val repo = FakeSettingsRepository().apply { readFailure = IOException("read") }
    val vm = model(repo)
    runCurrent()
    assertTrue(vm.uiState.value.readFailed)
    repo.readFailure = null
    vm.retry()
    runCurrent()
    assertFalse(vm.uiState.value.readFailed)
    assertEquals(DeviceSettings(), vm.uiState.value.settings)
  }

  @Test
  fun pendingWriteAndLocalSuccessDoNotDependOnObservationEcho() = runTest {
    val gate = CompletableDeferred<Unit>()
    val repo =
        FakeSettingsRepository().apply {
          writeGate = gate
          emitWrites = false
        }
    val vm = model(repo)
    runCurrent()
    vm.selectAppearance(AppearancePreference.DARK)
    runCurrent()
    assertEquals(SettingsSavePhase.SAVING, vm.uiState.value.savePhase)
    assertEquals(AppearancePreference.FOLLOW_DEVICE, vm.uiState.value.settings!!.appearance)
    vm.selectAppearance(AppearancePreference.LIGHT)
    assertEquals(listOf(AppearancePreference.DARK), repo.writes)
    gate.complete(Unit)
    runCurrent()
    assertEquals(SettingsSavePhase.IDLE, vm.uiState.value.savePhase)
    assertEquals(AppearancePreference.DARK, vm.uiState.value.settings!!.appearance)
    assertEquals(AppearancePreference.FOLLOW_DEVICE, repo.settings.value.appearance)
  }

  @Test
  fun retryCancelsThePreviousObservation() = runTest {
    val repo = FakeSettingsRepository()
    val vm = model(repo)
    runCurrent()
    repeat(3) {
      vm.retry()
      runCurrent()
      assertEquals(1, repo.activeObservations)
    }
    repo.settings.value = DeviceSettings(AppearancePreference.DARK)
    runCurrent()
    assertEquals(AppearancePreference.DARK, vm.uiState.value.settings!!.appearance)
  }

  @Test
  fun cancellationIsNotShownAsAnError() = runTest {
    val repo = FakeSettingsRepository().apply { readFailure = CancellationException("cancel") }
    val vm = model(repo)
    runCurrent()
    assertFalse(vm.uiState.value.readFailed)
    repo.readFailure = null
    vm.retry()
    runCurrent()
    repo.writeFailure = CancellationException("cancel")
    vm.selectAppearance(AppearancePreference.DARK)
    runCurrent()
    assertFalse(vm.uiState.value.saveFailed)
    assertFalse(vm.uiState.value.saving)
  }
}
