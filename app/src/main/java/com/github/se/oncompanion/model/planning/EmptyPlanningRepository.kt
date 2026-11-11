// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Empty source for the initial Planning tab until owning-feature repositories are connected. Emits
 * immediately without Firebase or sample appointments; Planning has no store of its own.
 */
object EmptyPlanningRepository : PlanningRepository {
  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> = flowOf(emptyList())
}
