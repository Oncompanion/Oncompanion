// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import android.util.Log
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.domain.carecircle.SectionAccess
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What Planning and the Overview can show of someone's planning. */
sealed interface CarePlanning {

  /** Nothing to show yet: the access or the entries aren't known. Show no entries. */
  data object Loading : CarePlanning

  /** The planning's [items]. */
  data class Shown(val items: List<PlanningItem>) : CarePlanning

  /** The patient a caregiver follows doesn't share their planning with them. */
  data object NotShared : CarePlanning

  /** No planning to show: nobody is signed in, or the caregiver isn't in the patient's circle. */
  data object NoAccess : CarePlanning

  /**
   * The entries couldn't be read. The flow goes on, so an account or access change still loads the
   * next planning; a retry observes again.
   */
  data object Failed : CarePlanning
}

/**
 * The planning the signed-in user may see: their own, or that of the patient they follow when the
 * patient shares it (US-20), so Planning and the Overview have one code path for both. Whose
 * planning, and whether it is shared, comes from [resolveSection].
 */
class ObserveCarePlanning(
    private val resolveSection: ResolveSectionAccess = ResolveSectionAccess(),
    private val planning: PlanningRepository = PlanningRepositoryProvider.repository,
) {

  /**
   * Emits the planning within [range] of [patientUid] (null for the user's own), and again whenever
   * the access or the entries change. An account or access change starts over from
   * [CarePlanning.Loading], so the previous person's entries never show for the next one. A read
   * failure of [planning] is [CarePlanning.Failed]; the flow fails only if [resolveSection] does.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  operator fun invoke(patientUid: String?, range: PlanningRange): Flow<CarePlanning> =
      resolveSection(CarePermission.PLANNING, patientUid).flatMapLatest { access ->
        when (access) {
          SectionAccess.Loading -> flowOf(CarePlanning.Loading)
          SectionAccess.NoAccess -> flowOf(CarePlanning.NoAccess)
          SectionAccess.NotShared -> flowOf(CarePlanning.NotShared)
          is SectionAccess.Readable -> shown(access.ownerUid, range)
        }
      }

  /** [ownerUid]'s entries, from [CarePlanning.Loading]; a read failure is [CarePlanning.Failed]. */
  private fun shown(ownerUid: String, range: PlanningRange): Flow<CarePlanning> =
      planning
          .observeItems(ownerUid, range)
          .map<List<PlanningItem>, CarePlanning> { CarePlanning.Shown(it) }
          .onStart { emit(CarePlanning.Loading) }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Couldn't read the planning", e)
            emit(CarePlanning.Failed)
          }

  private companion object {
    const val TAG = "ObserveCarePlanning"
  }
}
