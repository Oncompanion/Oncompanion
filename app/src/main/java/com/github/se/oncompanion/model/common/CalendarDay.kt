// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.common

import com.google.firebase.Timestamp
import java.time.LocalDate
import java.time.ZoneId

/**
 * The time zone calendar days are stored in (a prescription's date, a date of birth...). A day is
 * saved as the Timestamp of its midnight there, never in the phone's time zone, so it reads back as
 * the same day wherever the phone is.
 */
private val CALENDAR_DAY_ZONE: ZoneId = ZoneId.of("Europe/Zurich")

/** This day as stored in Firestore: the Timestamp of its midnight in [CALENDAR_DAY_ZONE]. */
internal fun LocalDate.toCalendarDayTimestamp(): Timestamp =
    Timestamp(atStartOfDay(CALENDAR_DAY_ZONE).toInstant())

/** The day stored by [toCalendarDayTimestamp]. */
internal fun Timestamp.toCalendarDay(): LocalDate =
    toInstant().atZone(CALENDAR_DAY_ZONE).toLocalDate()
