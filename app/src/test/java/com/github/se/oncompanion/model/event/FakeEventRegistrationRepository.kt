// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [EventRegistrationRepository] shared by unit tests, separated by uid. Like Firestore,
 * confirming twice keeps one confirmation. It doesn't check whether the event has started: that is
 * the rules' job, tested on the emulator.
 */
class FakeEventRegistrationRepository : EventRegistrationRepository {
  private val store = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

  /** When set, observing fails with this exception when collection starts. */
  var observeError: Exception? = null

  /** When set, confirming and removing fail with this exception without changing anything. */
  var writeError: Exception? = null

  /** The events [uid] confirmed, for assertions. */
  fun registeredEventIds(uid: String): Set<String> = store.value[uid].orEmpty()

  override fun observeIsRegistered(uid: String, eventId: String): Flow<Boolean> {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    return flow {
      observeError?.let { throw it }
      emitAll(store.map { eventId in it[uid].orEmpty() }.distinctUntilChanged())
    }
  }

  override fun confirm(uid: String, eventId: String) {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    writeError?.let { throw it }
    store.update { it + (uid to (it[uid].orEmpty() + eventId)) }
  }

  override fun remove(uid: String, eventId: String) {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    writeError?.let { throw it }
    store.update { it + (uid to (it[uid].orEmpty() - eventId)) }
  }

  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }
}
