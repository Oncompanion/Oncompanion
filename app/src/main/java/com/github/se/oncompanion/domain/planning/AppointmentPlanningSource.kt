// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Adapts a patient's cached appointments to the calendar's display contract. Whose appointments
 * they are is decided by the caller ([ObserveCarePlanning]), which restarts the observation when it
 * changes; read failures reach the caller.
 */
class AppointmentPlanningSource(private val appointments: AppointmentRepository) :
    PlanningRepository {

  override fun observeItems(ownerUid: String, range: PlanningRange): Flow<List<PlanningItem>> =
      appointments.observeAppointments(ownerUid).map { entries ->
        // Filtering preserves the appointment repository's time and ID ordering.
        entries
            .filter { it.scheduledAt in range }
            .map { appointment ->
              PlanningItem(
                  source = PlanningSource.Appointment(appointment.id),
                  timing = PlanningTiming.Timed(appointment.scheduledAt),
                  title = appointment.title,
                  subtitle = appointment.location?.takeIf { it.isNotBlank() },
              )
            }
      }
}
