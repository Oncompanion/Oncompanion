package com.github.se.oncompanion.model.event

import kotlinx.coroutines.flow.Flow

/** Reads the Ligue's support events. Events are the same for every user and read-only. */
interface EventRepository {

  /** Emits the upcoming events (starting from now), soonest first, and again when they change. */
  fun getUpcomingEvents(): Flow<List<Event>>

  /** Returns the event with [id], or `null` if it doesn't exist. */
  suspend fun getEvent(id: String): Event?
}
