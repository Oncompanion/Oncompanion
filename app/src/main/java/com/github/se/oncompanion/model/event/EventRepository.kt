// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Reads the Ligue's support events. Events are the same for every user and read-only. */
interface EventRepository {

  /** Emits the upcoming events (starting from now), soonest first, and again when they change. */
  fun getUpcomingEvents(): Flow<List<Event>>

  /**
   * Emits the events starting from [from] (included) until [until] (excluded), soonest first, and
   * again when they change. Unlike [getUpcomingEvents], it includes past events, so Planning can
   * show the weeks before today.
   */
  fun observeEvents(from: Instant, until: Instant): Flow<List<Event>>

  /** Returns the event with [id], or `null` if it doesn't exist. */
  suspend fun getEvent(id: String): Event?
}
