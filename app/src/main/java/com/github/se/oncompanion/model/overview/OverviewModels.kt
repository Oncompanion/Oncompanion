package com.github.se.oncompanion.model.overview

import java.time.LocalDateTime

/**
 * The user's next appointment, as shown on the Overview.
 *
 * @property title what the appointment is (e.g. "Oncology check-up")
 * @property dateTime when it starts, in local time
 * @property doctor who the appointment is with, if known
 * @property place where it takes place, if known
 */
data class NextAppointment(
    val title: String,
    val dateTime: LocalDateTime,
    val doctor: String? = null,
    val place: String? = null,
)

/** What a [TodayItem] is, which decides its icon. */
enum class TodayItemKind {
  MEDICATION,
  EVENT,
  APPOINTMENT,
}

/**
 * Something planned today, as shown in the Overview's "Today" list.
 *
 * Whether an item is over depends on the time, so the Overview decides it: this model only holds
 * what the user entered.
 *
 * @property id unique among today's items
 * @property time when it is planned (or starts), in local time
 * @property endTime when it ends, if known (events and appointments)
 * @property subtitle a short detail line (dose, place...), if any
 * @property isTaken for a medication, whether the user confirmed the intake; ignored otherwise
 */
data class TodayItem(
    val id: String,
    val kind: TodayItemKind,
    val time: LocalDateTime,
    val title: String,
    val endTime: LocalDateTime? = null,
    val subtitle: String? = null,
    val isTaken: Boolean = false,
)
