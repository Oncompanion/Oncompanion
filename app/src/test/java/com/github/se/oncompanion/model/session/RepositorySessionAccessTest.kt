// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryFirestore
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Recently merged entry points must reject work before touching an ended session's SDK. */
class RepositorySessionAccessTest {
  private val noDatabase: () -> FirebaseFirestore = { error("Firestore was reached") }
  private val appointments = AppointmentRepositoryFirestore(noDatabase)
  private val invites = CareCircleInviteRepositoryFirestore(noDatabase)
  private val circle = CareCircleRepositoryFirestore(noDatabase)
  private val medications = MedicationRepositoryFirestore(noDatabase)
  private val registrations = EventRegistrationRepositoryFirestore(noDatabase)

  @After
  fun restore() {
    SessionAccess.reopen()
  }

  @Test
  fun newWritesAndJoinedCircleReadCannotStartWhileFrozen() = runTest {
    SessionAccess.freeze()
    val calls: List<suspend () -> Any?> =
        listOf(
            {
              appointments.updateAppointment(
                  "one",
                  Appointment(
                      id = "id",
                      title = "Draft",
                      scheduledAt = Instant.EPOCH,
                      type = AppointmentType.OTHER,
                  ),
              )
            },
            { appointments.deleteAppointment("one", "id") },
            { invites.hasJoinedCircle("one") },
            { invites.redeem("ABCDE-FGHJK", "one", "one@example.com") },
            { registrations.confirm("one", "event") },
            { registrations.remove("one", "event") },
            { medications.newId() },
        )
    for (call in calls) {
      try {
        call()
        fail("Old session operation was allowed")
      } catch (error: IllegalStateException) {
        assertEquals("Sign-out is in progress", error.message)
      }
    }
  }

  @Test
  fun detailAndCaregiverAndRegistrationFlowsCannotReopenInAnotherSession() = runTest {
    // Constructing a flow must not eagerly capture a Firestore instance or document reference.
    val flows =
        listOf(
            appointments.observeAppointment("one", "id"),
            medications.observePrescription("one", "id"),
            medications.observePrescriptions("one"),
            medications.observeMedications("one"),
            circle.observeMember("patient", "one"),
            invites.observeJoinedCircles("one"),
            registrations.observeIsRegistered("one", "event"),
        )
    SessionAccess.stopListeners()
    SessionAccess.reopen()
    for (flow in flows) {
      try {
        flow.first()
        fail("Old session flow was allowed")
      } catch (error: IllegalStateException) {
        assertEquals("The session has ended", error.message)
      }
    }
  }
}
