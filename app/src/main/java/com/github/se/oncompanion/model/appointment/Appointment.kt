package com.github.se.oncompanion.model.appointment

import java.time.Instant

/** The category recorded by the patient, without interpreting their treatment. */
enum class AppointmentType {
  TREATMENT,
  CONSULTATION,
  OTHER,
}

/**
 * An appointment owned by a patient at `/users/{uid}/appointments/{id}`.
 *
 * [scheduledAt] is the actual appointment instant; views display it in the phone's time zone.
 * Unlike a calendar-only date, it is not normalized to midnight in Europe/Zurich.
 *
 * @property id the document ID; empty before the appointment is saved
 * @property title what the appointment is for, as entered by the patient
 * @property scheduledAt the recorded date and time, allowing past and future appointments
 * @property type the category chosen by the patient
 * @property location optional free text, also usable without hospital search
 * @property notes optional information the patient wants to keep
 * @property createdAt the server creation timestamp; null before acknowledgement
 */
data class Appointment(
    val id: String = "",
    val title: String,
    val scheduledAt: Instant,
    val type: AppointmentType,
    val location: String? = null,
    val notes: String? = null,
    val createdAt: Instant? = null,
) {
  /**
   * Whether the patient-entered fields fit the storage schema. The repository assigns [id] and
   * [createdAt] on creation, so they do not affect validation.
   */
  fun isValid(): Boolean =
      title.isNotBlank() &&
          title.length <= MAX_TITLE_LENGTH &&
          scheduledAt >= MIN_SCHEDULED_AT &&
          scheduledAt <= MAX_SCHEDULED_AT &&
          (location == null || location.length <= MAX_LOCATION_LENGTH) &&
          (notes == null || notes.length <= MAX_NOTES_LENGTH)

  companion object {
    /** Maximum title length; keep in sync with the appointment security rules. */
    const val MAX_TITLE_LENGTH = 100

    /** Maximum location length; keep in sync with the appointment security rules. */
    const val MAX_LOCATION_LENGTH = 200

    /** Maximum notes length; keep in sync with the appointment security rules. */
    const val MAX_NOTES_LENGTH = 1000

    /** Earliest instant supported by a Firestore timestamp. */
    val MIN_SCHEDULED_AT: Instant = Instant.parse("0001-01-01T00:00:00Z")

    /** Latest instant supported by a Firestore timestamp, including nanosecond precision. */
    val MAX_SCHEDULED_AT: Instant = Instant.parse("9999-12-31T23:59:59.999999999Z")
  }
}
