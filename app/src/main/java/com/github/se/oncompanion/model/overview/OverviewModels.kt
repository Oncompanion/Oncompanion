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
 * @property id unique among today's items
 * @property time when it is planned, in local time
 * @property subtitle a short detail line (dose, place...), if any
 * @property isDone true once it's over or, for a medication, taken
 */
data class TodayItem(
    val id: String,
    val kind: TodayItemKind,
    val time: LocalDateTime,
    val title: String,
    val subtitle: String? = null,
    val isDone: Boolean = false,
)
