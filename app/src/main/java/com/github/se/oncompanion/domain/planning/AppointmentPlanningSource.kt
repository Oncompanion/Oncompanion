package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/** Adapts the signed-in patient's cached appointments to the calendar's display contract. */
class AppointmentPlanningSource(
    private val appointments: AppointmentRepository,
    private val auth: AuthRepository,
) : PlanningRepository {
  /** Clears the previous patient's agenda on account changes; read failures reach the caller. */
  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      auth
          .observeCurrentUser()
          .map { it?.uid }
          .distinctUntilChanged()
          .transformLatest { uid ->
            // Clear old entries before collecting a different patient's cache, including on retry.
            emit(Result.success(emptyList<PlanningItem>()))
            if (uid != null) {
              emitAll(
                  appointments
                      .observeAppointments(uid)
                      .map { entries ->
                        // Filtering preserves the appointment repository's time and ID ordering.
                        Result.success(
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
                        )
                      }
                      .catch { error ->
                        if (error is CancellationException) throw error
                        // Send the failure as a value so the clear reaches the UI before it is
                        // thrown.
                        emit(Result.failure(error))
                      }
              )
            }
          }
          // Do not queue snapshots from a patient whose observation has been cancelled.
          .buffer(0)
          .map { it.getOrThrow() }
}
