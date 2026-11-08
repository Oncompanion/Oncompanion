// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import android.util.Log
import com.github.se.oncompanion.domain.carecircle.ResolveSectionAccess
import com.github.se.oncompanion.domain.carecircle.SectionAccess
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.addedByName
import com.github.se.oncompanion.model.carecircle.observeMemberNames
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** What Planning and the Overview can show of someone's planning. */
sealed interface CarePlanning {

  /** Nothing to show yet: the access or the entries aren't known. Show no entries. */
  data object Loading : CarePlanning

  /**
   * The planning's [items].
   *
   * @property canAdd whether adding an appointment may be offered: in the user's own planning, or
   *   when the patient lets the caregiver add to it (US-41)
   */
  data class Shown(val items: List<PlanningItem>, val canAdd: Boolean = false) : CarePlanning

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
 * planning, and whether it is shared, comes from [resolveSection]. In the patient's own planning,
 * an appointment a caregiver added shows the name the patient gave them in [careCircle].
 */
class ObserveCarePlanning(
    private val resolveSection: ResolveSectionAccess = ResolveSectionAccess(),
    private val planning: PlanningRepository = PlanningRepositoryProvider.repository,
    private val careCircle: CareCircleRepository = CareCircleRepositoryProvider.repository,
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
        planningFor(access, range)
      }

  /** What the user sees of the planning with [access]. */
  private fun planningFor(access: SectionAccess, range: PlanningRange): Flow<CarePlanning> =
      when (access) {
        SectionAccess.Loading -> flowOf(CarePlanning.Loading)
        SectionAccess.NoAccess -> flowOf(CarePlanning.NoAccess)
        SectionAccess.NotShared -> flowOf(CarePlanning.NotShared)
        is SectionAccess.Readable -> shown(access, range)
      }

  /**
   * The entries [access] lets the user read, from [CarePlanning.Loading]; a read failure is
   * [CarePlanning.Failed].
   */
  private fun shown(access: SectionAccess.Readable, range: PlanningRange): Flow<CarePlanning> =
      planning
          .observeItems(access.ownerUid, range)
          .withAddedByNames(access)
          .map<List<PlanningItem>, CarePlanning> { CarePlanning.Shown(it, access.canAdd) }
          .onStart { emit(CarePlanning.Loading) }
          .catch { e ->
            if (e is CancellationException) throw e
            Log.e(TAG, "Couldn't read the planning", e)
            emit(CarePlanning.Failed)
          }

  /**
   * For the patient, who added each entry as they named that member in their circle (a rename
   * shows); a caregiver gets no names and keeps the stored ones. The entries never wait for the
   * circle (offline, it may not be in the cache yet): they show with the stored names, and again
   * with the circle's once it is read. A read error reaches the caller after the entries before it.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun Flow<List<PlanningItem>>.withAddedByNames(
      access: SectionAccess.Readable
  ): Flow<List<PlanningItem>> {
    if (!access.canEdit) return this
    val entries = this
    return careCircle
        .observeMemberNames(access.ownerUid)
        .distinctUntilChanged()
        .flatMapLatest { names ->
          entries
              .map { items ->
                Result.success(
                    items.map {
                      it.copy(addedByName = addedByName(it.addedBy, it.addedByName, names))
                    }
                )
              }
              // Passed on as a value, so the entries emitted before it are never dropped
              .catch { e ->
                if (e is CancellationException) throw e
                emit(Result.failure(e))
              }
        }
        .map { it.getOrThrow() }
  }

  private companion object {
    const val TAG = "ObserveCarePlanning"
  }
}
