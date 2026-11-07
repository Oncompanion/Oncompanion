// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Shared observable source for Planning tests, including explicit failures and retry behavior. */
class FakePlanningRepository : PlanningRepository {
  val items = MutableStateFlow<List<PlanningItem>>(emptyList())
  val ranges = mutableListOf<PlanningRange>()
  /** Whose planning each observation asked for, in order. */
  val owners = mutableListOf<String>()
  /** The sections each observation asked for, in order; null when it asked for every entry. */
  val sections = mutableListOf<Set<CarePermission>?>()
  var fail = false
  var observation: ((PlanningRange) -> Flow<List<PlanningItem>>)? = null

  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> {
    this.sections.add(sections)
    return observe(ownerUid, range)
  }

  override fun observeItems(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> {
    sections.add(null)
    return observe(ownerUid, range)
  }

  private fun observe(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> {
    owners.add(ownerUid)
    ranges.add(range)
    return observation?.invoke(range)
        ?: if (fail) flow { throw IllegalStateException("Read failed") } else items
  }
}
