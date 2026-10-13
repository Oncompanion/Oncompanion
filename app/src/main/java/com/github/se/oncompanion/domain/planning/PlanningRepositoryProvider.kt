// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningRepository

/**
 * Holds the [PlanningRepository] the app uses, so ViewModels name the interface and never the
 * source behind it. Tests replace [repository] with a fake.
 *
 * It lives here and not next to the interface because its default is built in this layer.
 */
object PlanningRepositoryProvider {
  /**
   * The signed-in patient's appointments by default. The appointment and auth providers are read on
   * each observation, so replacing them also reaches Planning.
   */
  @Volatile
  var repository: PlanningRepository = PlanningRepository { range ->
    AppointmentPlanningSource(
            AppointmentRepositoryProvider.repository,
            AuthRepositoryProvider.repository,
        )
        .observeItems(range)
  }
}
