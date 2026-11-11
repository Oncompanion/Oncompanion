// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryProvider
import com.github.se.oncompanion.model.event.EventRepositoryProvider
import com.github.se.oncompanion.model.medication.MedicationRepositoryProvider
import com.github.se.oncompanion.model.planning.PlanningRepository

/**
 * Holds the [PlanningRepository] the app uses, so ViewModels name the interface and never the
 * source behind it. Tests replace [repository] with a fake.
 *
 * It lives here and not next to the interface because its default is built in this layer.
 */
object PlanningRepositoryProvider {
  /**
   * By default, a patient's appointments and medications and the Ligue events they confirmed,
   * merged by [ObservePlanning]. Events are an [OptionalPlanningSource]: if they can't be read,
   * Planning still shows the appointments instead of an error. Medications and events are a
   * [SectionPlanningSource]: a caregiver only gets them when the patient also shares Prescriptions
   * or Events. The providers are read on each observation, so replacing them also reaches Planning.
   * Whose planning it is, and restarting it on an account change, is decided by
   * [ObserveCarePlanning].
   */
  @Volatile
  var repository: PlanningRepository = PlanningRepository { ownerUid, range, sections ->
    sources().observeItems(ownerUid, range, sections)
  }

  /** The sources of the default [repository], built from the providers' current repositories. */
  private fun sources() =
      ObservePlanning(
          AppointmentPlanningSource(AppointmentRepositoryProvider.repository),
          SectionPlanningSource(
              CarePermission.PRESCRIPTIONS,
              MedicationPlanningSource(MedicationRepositoryProvider.repository),
          ),
          SectionPlanningSource(
              CarePermission.EVENTS,
              // Events are nice to have: if they fail, the appointments still show
              OptionalPlanningSource(
                  EventPlanningSource(
                      EventRepositoryProvider.repository,
                      EventRegistrationRepositoryProvider.repository,
                  )
              ),
          ),
      )
}
