// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningRepository

/**
 * Holds the [PlanningRepository] the app uses, so ViewModels name the interface and never the
 * source behind it. Tests replace [repository] with a fake.
 *
 * It lives here and not next to the interface because its default is built in this layer.
 */
object PlanningRepositoryProvider {
  /**
   * By default, the signed-in patient's appointments and the Ligue's upcoming events, merged by
   * [ObservePlanning]. Events are an [OptionalPlanningSource]: if they can't be read, Planning
   * still shows the appointments instead of an error. The providers are read on each observation,
   * so replacing them also reaches Planning.
   */
  @Volatile
  var repository: PlanningRepository = PlanningRepository { range ->
    ObservePlanning(
            AppointmentPlanningSource(
                AppointmentRepositoryProvider.repository,
                AuthRepositoryProvider.repository,
            ),
            // Events are nice to have: if they fail, the appointments still show
            OptionalPlanningSource(EventPlanningSource(EventRepositoryProvider.repository)),
        )
        .observeItems(range)
  }

  /**
   * What a caregiver sees in Planning and the Overview until #240 reads the chosen patient's data:
   * only the Ligue's events, the same for everyone. Never the signed-in caregiver's own
   * appointments, which would look like the patient's.
   */
  @Volatile
  var publicEvents: PlanningRepository = PlanningRepository { range ->
    OptionalPlanningSource(EventPlanningSource(EventRepositoryProvider.repository))
        .observeItems(range)
  }
}
