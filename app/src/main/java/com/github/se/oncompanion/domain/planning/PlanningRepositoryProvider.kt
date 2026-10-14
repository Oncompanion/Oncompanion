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
   * [ObservePlanning]. The providers are read on each observation, so replacing them also reaches
   * Planning.
   */
  @Volatile
  var repository: PlanningRepository = PlanningRepository { range ->
    ObservePlanning(
            AppointmentPlanningSource(
                AppointmentRepositoryProvider.repository,
                AuthRepositoryProvider.repository,
            ),
            EventPlanningSource(EventRepositoryProvider.repository),
        )
        .observeItems(range)
  }
}
