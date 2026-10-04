package com.github.se.oncompanion.model.overview

import kotlinx.coroutines.flow.Flow

/**
 * What the Overview shows about the user's day. It only reads what the user entered elsewhere
 * (Planning, Prescriptions, Events): the Overview never decides anything about a treatment.
 *
 * Use [FakeOverviewRepository] until an implementation reads the planning data.
 */
interface OverviewRepository {

  /** Emits the next upcoming appointment, or `null` if there is none, and again when it changes. */
  fun observeNextAppointment(): Flow<NextAppointment?>

  /** Emits today's items, in any order, and again when they change. */
  fun observeTodayItems(): Flow<List<TodayItem>>
}
