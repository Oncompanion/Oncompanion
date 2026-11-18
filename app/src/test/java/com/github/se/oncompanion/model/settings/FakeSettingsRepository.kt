// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Shared controllable device preferences for screen, ViewModel and root-theme tests. */
class FakeSettingsRepository(initial: DeviceSettings = DeviceSettings()) : SettingsRepository {
  val settings = MutableStateFlow(initial)
  /** Failure injected into an already-running observation. */
  val observationFailure = MutableStateFlow<Exception?>(null)
  /** Number of live collectors, used to detect duplicate observations after Retry. */
  var activeObservations = 0
    private set

  /** Lets a test keep a local write pending. */
  var writeGate: CompletableDeferred<Unit>? = null
  /** Suppresses the observation echo so tests exercise the ViewModel's successful local update. */
  var emitWrites = true
  val writes = mutableListOf<AppearancePreference>()
  /** Suspends each new observation until a test releases startup. */
  var readGate: CompletableDeferred<Unit>? = null
  var readFailure: Exception? = null
  var writeFailure: Exception? = null

  override fun observeSettings(): Flow<DeviceSettings> = flow {
    activeObservations++
    try {
      readGate?.await()
      readFailure?.let { throw it }
      emitAll(
          settings.combine(observationFailure) { current, failure ->
            failure?.let { throw it }
            current
          }
      )
    } finally {
      activeObservations--
    }
  }

  override suspend fun setAppearance(appearance: AppearancePreference) {
    writes += appearance
    writeGate?.await()
    writeFailure?.let { throw it }
    if (emitWrites) settings.value = settings.value.copy(appearance = appearance)
  }
}
