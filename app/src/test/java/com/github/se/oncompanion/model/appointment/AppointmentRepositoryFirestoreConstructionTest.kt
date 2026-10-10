package com.github.se.oncompanion.model.appointment

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ensures default construction and argument validation never require initialized Firebase. */
class AppointmentRepositoryFirestoreConstructionTest {
  /** Allows a ViewModel to construct its default repository in a plain unit test. */
  @Test
  fun defaultConstructorDoesNotInitializeFirebase() {
    AppointmentRepositoryFirestore()
  }

  /** Defers Firebase access until a valid observation is actually collected. */
  @Test
  fun observationAccessesFirebaseOnlyWhenCollected() =
      runTest(StandardTestDispatcher()) {
        var requested = false
        val failure = IllegalStateException("Provider invoked")
        val repository = AppointmentRepositoryFirestore {
          requested = true
          throw failure
        }
        val source = repository.observeAppointments("alice")
        assertFalse(requested)
        val error = runCatching { source.first() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(failure.message, error?.message)
        assertTrue(requested)
      }

  /** Rejects invalid user/document paths and appointments before asking for Firebase. */
  @Test
  fun invalidInputDoesNotInitializeFirebase() =
      runTest(StandardTestDispatcher()) {
        val repository = AppointmentRepositoryFirestore { error("Firebase must not be accessed") }
        val valid =
            Appointment(
                title = "Consultation",
                scheduledAt = Appointment.MIN_SCHEDULED_AT,
                type = AppointmentType.CONSULTATION,
            )
        for (uid in listOf("", " ", "alice/nested")) {
          assertTrue(
              runCatching { repository.observeAppointments(uid) }.exceptionOrNull()
                  is IllegalArgumentException
          )
          assertTrue(
              runCatching { repository.getAppointment(uid, "entry") }.exceptionOrNull()
                  is IllegalArgumentException
          )
          assertTrue(
              runCatching { repository.addAppointment(uid, valid) }.exceptionOrNull()
                  is IllegalArgumentException
          )
        }
        for (id in listOf("", " ", "entry/nested")) {
          assertTrue(
              runCatching { repository.getAppointment("alice", id) }.exceptionOrNull()
                  is IllegalArgumentException
          )
        }
        assertTrue(
            runCatching { repository.addAppointment("alice", valid.copy(title = "")) }
                .exceptionOrNull() is IllegalArgumentException
        )
      }
}
