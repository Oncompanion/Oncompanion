// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.RejectingServer
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
 * the rules' job, tested on the emulator. [serverRejectsWrites] and [rejectPendingWrites] simulate
 * a server that rejects writes after they returned.
 *
 * @param rejectedWrites where [rejectPendingWrites] reports the writes it undoes
 */
class FakeEventRegistrationRepository(rejectedWrites: RejectedWrites = RejectedWrites()) :
    EventRegistrationRepository {
  private val store = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

  /** When set, observing fails with this exception when collection starts. */
  var observeError: Exception? = null

  /** When set, confirming and removing fail with this exception without changing anything. */
  var writeError: Exception? = null

  private val server = RejectingServer<Map<String, Set<String>>>(rejectedWrites)

  /** When true, writes still return as usual, until [rejectPendingWrites] undoes them. */
  var serverRejectsWrites by server::rejectsWrites

  /**
   * The server rejects the writes saved since [serverRejectsWrites]: they are undone and reported.
   */
  fun rejectPendingWrites() = server.reject { store.value = it }

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

  override fun observeRegisteredEventIds(uid: String): Flow<Set<String>> {
    requirePathSegment(uid)
    return flow {
      observeError?.let { throw it }
      emitAll(store.map { it[uid].orEmpty() }.distinctUntilChanged())
    }
  }

  override fun confirm(uid: String, eventId: String) {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    writeError?.let { throw it }
    server.saving(store.value)
    store.update { it + (uid to (it[uid].orEmpty() + eventId)) }
  }

  override fun remove(uid: String, eventId: String) {
    requirePathSegment(uid)
    requirePathSegment(eventId)
    writeError?.let { throw it }
    server.saving(store.value)
    store.update { it + (uid to (it[uid].orEmpty() - eventId)) }
  }

  private fun requirePathSegment(value: String) {
    require(value.isNotBlank() && '/' !in value) { "Invalid document path segment" }
  }
}
