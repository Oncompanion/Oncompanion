package com.github.se.oncompanion.model.planning

import java.time.Instant

/** An occurrence displayed in Planning, derived from its owning feature's data. */
data class PlanningItem(
    val source: PlanningSource,
    val scheduledAt: Instant,
    val title: String,
    val subtitle: String? = null,
) {
  val key: String
    get() = source.key
}

/** Identifies the feature that owns an occurrence and handles its editing. */
sealed interface PlanningSource {
  val key: String

  data class Appointment(val appointmentId: String) : PlanningSource {
    init {
      require(appointmentId.isNotBlank()) { "Appointment ID must not be blank" }
    }

    override val key: String
      get() = "appointment:$appointmentId"
  }
}
