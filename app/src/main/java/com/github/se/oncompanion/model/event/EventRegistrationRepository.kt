// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import kotlinx.coroutines.flow.Flow

/**
 * The patient's confirmations that they registered for an event with its organizer (US-25).
 * Oncompanion only stores what the patient says: it never registers them with the organizer and
 * doesn't check it.
 *
 * Writes return once saved on the device, without waiting for the server. If the server later
 * rejects one, the change is undone on the device and reported through
 * [com.github.se.oncompanion.model.session.RejectedWrites], which tells the user.
 */
interface EventRegistrationRepository {

  /**
   * Emits [uid]'s confirmation for [eventId], or null when there is none, now and on every change.
   */
  fun observeRegistration(uid: String, eventId: String): Flow<EventRegistration?>

  /**
   * Emits the ids of the events [uid] confirmed, now and on every change. Planning shows these
   * events, including past ones: a confirmation is kept after its event, even when the event page
   * no longer shows it.
   */
  fun observeRegisteredEventIds(uid: String): Flow<Set<String>>

  /**
   * Saves that [uid] registered for [eventId], signed with [by] when a caregiver confirms it for
   * them (US-42). Confirming again keeps a single confirmation. Only events that haven't started
   * can be confirmed, and a caregiver can't replace an existing confirmation: the backend rejects
   * the others. Returns right away, without waiting for the server, so it works offline.
   */
  fun confirm(uid: String, eventId: String, by: EventRegistration = EventRegistration())

  /** Removes [uid]'s confirmation for [eventId], if any. Returns right away, like [confirm]. */
  fun remove(uid: String, eventId: String)
}
