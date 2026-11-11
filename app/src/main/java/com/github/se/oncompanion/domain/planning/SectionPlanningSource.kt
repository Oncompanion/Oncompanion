// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A Planning [source] whose entries belong to [section] of the patient's data, such as medications
 * (Prescriptions) or confirmed events (Events). A caregiver the patient doesn't share [section]
 * with gets none of its entries, and the source is not read at all: the rules would refuse the
 * read.
 */
class SectionPlanningSource(
    private val section: CarePermission,
    private val source: PlanningRepository,
) : PlanningRepository {

  /** The entries of [source] when [section] is among [sections], else none, without reading it. */
  override fun observeItems(
      ownerUid: String,
      range: PlanningRange,
      sections: Set<CarePermission>,
  ): Flow<List<PlanningItem>> =
      if (section in sections) source.observeItems(ownerUid, range, sections)
      else flowOf(emptyList())
}
