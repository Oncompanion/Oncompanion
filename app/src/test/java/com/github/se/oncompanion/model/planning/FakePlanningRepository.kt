// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.planning

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Shared observable source for Planning tests, including explicit failures and retry behavior. */
class FakePlanningRepository : PlanningRepository {
  val items = MutableStateFlow<List<PlanningItem>>(emptyList())
  val ranges = mutableListOf<PlanningRange>()
  /** Whose planning each observation asked for, in order. */
  val owners = mutableListOf<String>()
  var fail = false
  var observation: ((PlanningRange) -> Flow<List<PlanningItem>>)? = null

  override fun observeItems(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> {
    owners.add(ownerUid)
    ranges.add(range)
    return observation?.invoke(range)
        ?: if (fail) flow { throw IllegalStateException("Read failed") } else items
  }
}
