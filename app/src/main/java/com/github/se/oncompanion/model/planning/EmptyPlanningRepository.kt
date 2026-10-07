package com.github.se.oncompanion.model.planning

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Empty source for the initial Planning tab until owning-feature repositories are connected. Emits
 * immediately without Firebase or sample appointments; Planning has no store of its own.
 */
object EmptyPlanningRepository : PlanningRepository {
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> = flowOf(emptyList())
}
