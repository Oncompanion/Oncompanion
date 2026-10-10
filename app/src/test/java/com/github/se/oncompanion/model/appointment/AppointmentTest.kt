package com.github.se.oncompanion.model.appointment

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks appointment validity against the agreed storage constraints. */
class AppointmentTest {
  private val appointment =
      Appointment(
          title = "Oncology consultation",
          scheduledAt = Instant.parse("2026-10-15T08:00:00Z"),
          type = AppointmentType.CONSULTATION,
      )

  /** Accepts both required-only input and optional patient information. */
  @Test
  fun minimalAndCompleteAppointmentsAreValid() {
    assertTrue(appointment.isValid())
    assertTrue(appointment.copy(location = "HUG", notes = "Bring documents").isValid())
    assertTrue(appointment.copy(location = "", notes = "").isValid())
  }

  /** Accepts every category offered by the appointment form. */
  @Test
  fun everyRecordedCategoryIsValid() {
    AppointmentType.entries.forEach { assertTrue(appointment.copy(type = it).isValid()) }
  }

  /** Rejects titles that cannot identify an appointment. */
  @Test
  fun titleIsRequiredAndCannotContainOnlyWhitespace() {
    listOf("", " ", "\t\n").forEach { assertFalse(appointment.copy(title = it).isValid()) }
  }

  /** Enforces the agreed title boundary. */
  @Test
  fun titleAcceptsItsLimitAndRejectsOneMoreCharacter() {
    assertTrue(appointment.copy(title = "a".repeat(100)).isValid())
    assertFalse(appointment.copy(title = "a".repeat(101)).isValid())
  }

  /** Enforces the agreed location boundary. */
  @Test
  fun locationAcceptsItsLimitAndRejectsOneMoreCharacter() {
    assertTrue(appointment.copy(location = "a".repeat(200)).isValid())
    assertFalse(appointment.copy(location = "a".repeat(201)).isValid())
  }

  /** Enforces the agreed notes boundary. */
  @Test
  fun notesAcceptTheirLimitAndRejectOneMoreCharacter() {
    assertTrue(appointment.copy(notes = "a".repeat(1000)).isValid())
    assertFalse(appointment.copy(notes = "a".repeat(1001)).isValid())
  }

  /** Keeps patient text intact across ordinary Unicode input. */
  @Test
  fun patientTextIsPreservedIncludingAccentsAndUnicode() {
    val entry = appointment.copy(title = "Contrôle 🗓", location = "Genève", notes = "持参する書類")
    assertTrue(entry.isValid())
    assertEquals("Contrôle 🗓", entry.title)
    assertEquals("Genève", entry.location)
    assertEquals("持参する書類", entry.notes)
  }

  /** Allows historical records as well as upcoming appointments. */
  @Test
  fun bothPastAndFutureAppointmentsAreValid() {
    assertTrue(appointment.copy(scheduledAt = Instant.parse("2000-01-01T00:00:00Z")).isValid())
    assertTrue(appointment.copy(scheduledAt = Instant.parse("2100-01-01T00:00:00Z")).isValid())
  }

  /** Accepts both supported timestamp endpoints. */
  @Test
  fun scheduledTimeAcceptsFirestoreBoundaries() {
    assertTrue(appointment.copy(scheduledAt = Instant.parse("0001-01-01T00:00:00Z")).isValid())
    assertTrue(
        appointment.copy(scheduledAt = Instant.parse("9999-12-31T23:59:59.999999999Z")).isValid()
    )
  }

  /** Rejects instants the storage implementation cannot serialize. */
  @Test
  fun scheduledTimeOutsideFirestoreRangeIsInvalid() {
    assertFalse(
        appointment
            .copy(scheduledAt = Instant.parse("0001-01-01T00:00:00Z").minusNanos(1))
            .isValid()
    )
    assertFalse(
        appointment
            .copy(scheduledAt = Instant.parse("9999-12-31T23:59:59.999999999Z").plusNanos(1))
            .isValid()
    )
  }

  /** Leaves identity and creation time assignment to the repository. */
  @Test
  fun creationMetadataDoesNotAffectInputValidation() {
    assertTrue(appointment.copy(id = "existing", createdAt = Instant.MIN).isValid())
  }
}
