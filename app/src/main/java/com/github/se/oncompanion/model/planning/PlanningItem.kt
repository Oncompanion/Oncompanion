package com.github.se.oncompanion.model.planning

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A display occurrence derived from an owning feature; never a separate stored record. */
data class PlanningItem(
    val source: PlanningSource,
    val timing: PlanningTiming,
    val title: String,
    val subtitle: String? = null,
    val frequency: String? = null,
) {
  init {
    require((source is PlanningSource.Medication) == (timing is PlanningTiming.DateOnly)) {
      "Medications need a calendar date; appointments and events need a time"
    }
    require(source is PlanningSource.Medication || frequency == null) {
      "Frequency belongs to a medication"
    }
  }

  /** Stable occurrence identity, independent of editable display text. */
  val key: String
    get() =
        "${source.key}:${when (val value = timing) {
      is PlanningTiming.Timed -> value.instant.toString()
      is PlanningTiming.DateOnly -> value.date.toString()
    }}"
}

/** A recorded time or a calendar day without an inferred intake time. */
sealed interface PlanningTiming {
  /** A timed appointment or event, displayed in the phone's time zone. */
  data class Timed(val instant: Instant) : PlanningTiming

  /** A medication listed on this calendar day, not an individual dose that is due. */
  data class DateOnly(val date: LocalDate) : PlanningTiming

  /** The agenda day; date-only entries never shift with the display zone. */
  fun dateIn(zoneId: ZoneId): LocalDate =
      when (this) {
        is Timed -> instant.atZone(zoneId).toLocalDate()
        is DateOnly -> date
      }
}

/** References the feature that owns the entry and will handle its detail/edit flow. */
sealed interface PlanningSource {
  /** Length-prefixed IDs avoid collisions even when IDs contain separators. */
  val key: String

  /** An appointment owned by the appointment feature. */
  data class Appointment(val appointmentId: String) : PlanningSource {
    init {
      require(appointmentId.isNotBlank()) { "Appointment ID must not be blank" }
    }

    override val key: String
      get() = "appointment:${appointmentId.length}:$appointmentId"
  }

  /** A Ligue support event, using the event repository's ID. */
  data class Event(val eventId: String) : PlanningSource {
    init {
      require(eventId.isNotBlank()) { "Event ID must not be blank" }
    }

    override val key: String
      get() = "event:${eventId.length}:$eventId"
  }

  /** A medication from a prescription, using the existing medication model's IDs. */
  data class Medication(val prescriptionId: String, val medicationId: String) : PlanningSource {
    init {
      require(prescriptionId.isNotBlank()) { "Prescription ID must not be blank" }
      require(medicationId.isNotBlank()) { "Medication ID must not be blank" }
    }

    override val key: String
      get() =
          "medication:${prescriptionId.length}:$prescriptionId:${medicationId.length}:$medicationId"
  }
}
