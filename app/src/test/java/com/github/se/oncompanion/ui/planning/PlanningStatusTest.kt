// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status an appointment or event row shows in Planning (redesign: "Appointment or event row").
 */
class PlanningStatusTest {

  private val zurich = ZoneId.of("Europe/Zurich")

  /** An entry from [start] to [end], local times in Zurich. */
  private fun entry(start: LocalDateTime, end: LocalDateTime?) =
      PlanningTiming.Timed(
          start.atZone(zurich).toInstant(),
          end?.atZone(zurich)?.toInstant(),
      )

  private fun at(day: Int, hour: Int, minute: Int = 0) =
      LocalDateTime.of(2026, 10, day, hour, minute)

  @Test
  fun anEntryIsUpcomingThenTodayThenHappeningNowThenPast() {
    val consultation = entry(at(20, 14), at(20, 15, 30))
    // (now, expected status)
    val cases =
        listOf(
            at(19, 20) to PlanningStatus.UPCOMING,
            at(20, 0) to PlanningStatus.TODAY,
            at(20, 13, 59) to PlanningStatus.TODAY,
            at(20, 14) to PlanningStatus.HAPPENING_NOW,
            at(20, 15, 29) to PlanningStatus.HAPPENING_NOW,
            at(20, 15, 30) to PlanningStatus.PAST,
            at(21, 9) to PlanningStatus.PAST,
        )

    for ((now, expected) in cases) {
      assertEquals(
          "At $now",
          expected,
          consultation.statusAt(now.atZone(zurich).toInstant(), zurich),
      )
    }
  }

  @Test
  fun anEntryOverMidnight_withoutAnEnd_andOnTheClockChangeDay() {
    // (entry, now, expected status)
    val cases =
        listOf(
            // Started yesterday evening and still going on
            Triple(entry(at(19, 22), at(20, 2)), at(20, 1), PlanningStatus.HAPPENING_NOW),
            // Without an end, it lasts an hour, as on the Overview
            Triple(entry(at(20, 14), null), at(20, 13), PlanningStatus.TODAY),
            Triple(entry(at(20, 14), null), at(20, 14), PlanningStatus.HAPPENING_NOW),
            Triple(entry(at(20, 14), null), at(20, 14, 59), PlanningStatus.HAPPENING_NOW),
            Triple(entry(at(20, 14), null), at(20, 15), PlanningStatus.PAST),
            // 25 October lasts 25 hours: 23:30 is still today in the evening
            Triple(entry(at(25, 23, 30), at(26, 0, 30)), at(25, 20), PlanningStatus.TODAY),
        )

    for ((entry, now, expected) in cases) {
      assertEquals("At $now", expected, entry.statusAt(now.atZone(zurich).toInstant(), zurich))
    }
  }

  @Test
  fun todayIsTheDayInThePhonesTimeZone() {
    // 10:00 in Zurich on the 20th; the entry starts at 23:00 in Zurich, already the 21st in Tokyo
    val entry = entry(at(20, 23), at(21, 0))
    val now = at(20, 10).atZone(zurich).toInstant()

    assertEquals(PlanningStatus.TODAY, entry.statusAt(now, zurich))
    assertEquals(PlanningStatus.UPCOMING, entry.statusAt(now, ZoneId.of("Asia/Tokyo")))
  }
}
