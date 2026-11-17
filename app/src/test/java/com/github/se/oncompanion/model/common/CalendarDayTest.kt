// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.common

import com.google.firebase.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarDayTest {

  /**
   * A day is stored as its midnight in Zurich and read back as the same day, whatever the phone.
   */
  @Test
  fun dayIsStoredAsMidnightInZurichAndReadBackUnchanged() {
    val cases =
        listOf(
            LocalDate.of(1985, 6, 15) to "1985-06-14T22:00:00Z", // summer time (CEST)
            LocalDate.of(2026, 1, 15) to "2026-01-14T23:00:00Z", // winter time (CET)
        )
    val phoneZone = TimeZone.getDefault()
    try {
      for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        for ((day, midnight) in cases) {
          val stored = day.toCalendarDayTimestamp()
          assertEquals("$day in $zone", Instant.parse(midnight), stored.toInstant())
          assertEquals("$day in $zone", day, stored.toCalendarDay())
        }
      }
    } finally {
      TimeZone.setDefault(phoneZone)
    }
  }

  @Test
  fun anyTimeOfTheDayInZurichReadsAsThatDay() {
    val lateEvening = Timestamp(Instant.parse("2026-01-15T22:59:59Z")) // 23:59:59 in Zurich
    assertEquals(LocalDate.of(2026, 1, 15), lateEvening.toCalendarDay())
  }
}
