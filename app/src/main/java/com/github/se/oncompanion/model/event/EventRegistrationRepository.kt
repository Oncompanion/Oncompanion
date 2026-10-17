// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import kotlinx.coroutines.flow.Flow

/**
 * The patient's confirmations that they registered for an event with its organizer (US-25).
 * Oncompanion only stores what the patient says: it never registers them with the organizer and
 * doesn't check it.
 */
interface EventRegistrationRepository {

  /** Emits whether [uid] confirmed their registration for [eventId], now and on every change. */
  fun observeIsRegistered(uid: String, eventId: String): Flow<Boolean>

  /**
   * Saves that [uid] registered for [eventId]. Confirming again keeps a single confirmation. Only
   * events that haven't started can be confirmed: the backend rejects the others. Returns right
   * away, without waiting for the server, so it works offline.
   */
  fun confirm(uid: String, eventId: String)

  /** Removes [uid]'s confirmation for [eventId], if any. Returns right away, like [confirm]. */
  fun remove(uid: String, eventId: String)
}
