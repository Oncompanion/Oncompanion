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
 * Adapts patient [uid]'s cached appointments to the calendar's display contract. [SignedInPlanning]
 * gives it the signed-in patient's uid.
 */
class AppointmentPlanningSource(
    private val appointments: AppointmentRepository,
    private val uid: String,
) : PlanningRepository {
  /** The patient's appointments within [range], updated live; read failures reach the caller. */
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      appointments.observeAppointments(uid).map { entries ->
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
