// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.planning.EmptyPlanningRepository
import com.github.se.oncompanion.model.planning.PlanningRepository

/**
 * Holds the [PlanningRepository] the app uses, so ViewModels name the interface and never the
 * source behind it. Tests replace [repository] with a fake.
 *
 * It lives here and not next to the interface because its default is built in this layer.
 */
object PlanningRepositoryProvider {
  /**
   * By default, the signed-in patient's [SignedInPlanning]: their appointments, medications and the
   * events they confirmed. It is built on each observation, so repository providers replaced later
   * (e.g. by tests) also reach Planning.
   */
  @Volatile
  var repository: PlanningRepository = PlanningRepository { range ->
    SignedInPlanning().observeItems(range)
  }

  /**
   * **Temporary, to replace in #240.** What a caregiver sees in Planning and the Overview: no
   * entries at all. Until #240 reads the chosen patient's data, a caregiver must not see:
   * - the signed-in caregiver's own appointments or event confirmations, which would look like the
   *   patient's;
   * - Ligue events the patient didn't confirm (US-30 shows only confirmed events).
   *
   * #240 should replace it with the chosen patient's planning, e.g. [EventPlanningSource] reading
   * that patient's confirmations.
   */
  @Volatile var caregiverPlaceholder: PlanningRepository = EmptyPlanningRepository
}
