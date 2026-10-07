package com.github.se.oncompanion.model.overview

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [OverviewRepository] holding fixed data, used until the Overview reads the planning data.
 *
 * It is empty by default, so the app never shows an appointment or a medication the user didn't
 * enter. Sample data only exists in the test sources.
 */
class FakeOverviewRepository(
    nextAppointment: NextAppointment? = null,
    todayItems: List<TodayItem> = emptyList(),
) : OverviewRepository {

  /** The appointment emitted by [observeNextAppointment]; change it to emit a new value. */
  val nextAppointment = MutableStateFlow(nextAppointment)

  /** The items emitted by [observeTodayItems]; change it to emit a new value. */
  val todayItems = MutableStateFlow(todayItems)

  override fun observeNextAppointment(): Flow<NextAppointment?> = nextAppointment

  override fun observeTodayItems(): Flow<List<TodayItem>> = todayItems
}
