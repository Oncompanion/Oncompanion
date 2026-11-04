// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepository
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.event.EventRegistrationRepository
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryProvider
import com.github.se.oncompanion.model.event.EventRepository
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningRange
import com.github.se.oncompanion.model.planning.PlanningRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * The signed-in patient's Planning: their appointments, medications and the events they confirmed,
 * merged by [ObservePlanning]. It is the only place Planning follows the signed-in account, so its
 * list always belongs to one patient:
 * - signed out, it shows no entries and reads nothing;
 * - on an account change, it clears every entry before reading the next patient's, so two patients'
 *   entries are never shown together;
 * - a read failure reaches the caller, after that clear.
 *
 * Events are an [OptionalPlanningSource]: if they can't be read, Planning still shows the
 * appointments and medications instead of an error.
 */
class SignedInPlanning(
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val appointments: AppointmentRepository = AppointmentRepositoryProvider.repository,
    private val medications: MedicationRepository = MedicationRepositoryProvider.repository,
    private val events: EventRepository = EventRepositoryProvider.repository,
    private val registrations: EventRegistrationRepository =
        EventRegistrationRepositoryProvider.repository,
) : PlanningRepository {

  /** The entries within [range] of whoever is signed in, following account changes. */
  @OptIn(ExperimentalCoroutinesApi::class)
  override fun observeItems(range: PlanningRange): Flow<List<PlanningItem>> =
      auth
          .observeCurrentUser()
          .map { it?.uid }
          .distinctUntilChanged()
          .transformLatest { uid ->
            // Clear every entry before reading a different patient's, including on retry
            emit(Result.success(emptyList<PlanningItem>()))
            if (uid != null) {
              emitAll(
                  planningOf(uid)
                      .observeItems(range)
                      .map { Result.success(it) }
                      .catch { error ->
                        if (error is CancellationException) throw error
                        // Send the failure as a value so the clear reaches the UI before it is
                        // thrown. Thrown here, it would cancel the collector and get past its
                        // catch (e.g. OptionalPlanningSource's).
                        emit(Result.failure(error))
                      }
              )
            }
          }
          // Do not queue lists from a patient whose observation has been cancelled
          .buffer(0)
          .map { it.getOrThrow() }

  /** [uid]'s sources, merged. Events are nice to have: if they fail, the rest still shows. */
  private fun planningOf(uid: String) =
      ObservePlanning(
          AppointmentPlanningSource(appointments, uid),
          MedicationPlanningSource(medications, uid),
          OptionalPlanningSource(EventPlanningSource(events, registrations, uid)),
      )
}
