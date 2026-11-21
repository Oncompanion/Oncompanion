// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.ui.planning.PlanningView.LIST
import com.github.se.oncompanion.ui.planning.PlanningView.MONTH
import com.github.se.oncompanion.ui.planning.PlanningView.THREE_DAYS
import com.github.se.oncompanion.ui.planning.PlanningView.WEEK
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Checks the days each Planning view covers and loads, its steps, and the supported years. */
class PlanningPeriodTest {
  private fun day(text: String) = LocalDate.parse(text)

  /** Each view's own days and the days it loads, around year ends and in months of 4 to 6 weeks. */
  @Test
  fun eachViewCoversAndLoadsItsDays() {
    // view, selected day -> start, end, load start, load end
    val cases =
        listOf(
            Triple(
                LIST,
                "2026-10-02",
                listOf("2026-09-28", "2026-10-05", "2026-09-28", "2026-10-05"),
            ),
            Triple(
                WEEK,
                "2026-10-04",
                listOf("2026-09-28", "2026-10-05", "2026-09-28", "2026-10-05"),
            ),
            Triple(
                WEEK,
                "2026-12-31",
                listOf("2026-12-28", "2027-01-04", "2026-12-28", "2027-01-04"),
            ),
            Triple(
                THREE_DAYS,
                "2026-12-31",
                listOf("2026-12-31", "2027-01-03", "2026-12-31", "2027-01-03"),
            ),
            // February 2027 starts on a Monday: exactly 4 weeks
            Triple(
                MONTH,
                "2027-02-14",
                listOf("2027-02-01", "2027-03-01", "2027-02-01", "2027-03-01"),
            ),
            // 5 weeks, with days of September and November
            Triple(
                MONTH,
                "2026-10-15",
                listOf("2026-10-01", "2026-11-01", "2026-09-28", "2026-11-02"),
            ),
            // March 2026 starts on a Sunday: 6 weeks
            Triple(
                MONTH,
                "2026-03-10",
                listOf("2026-03-01", "2026-04-01", "2026-02-23", "2026-04-06"),
            ),
            Triple(
                MONTH,
                "0001-01-20",
                listOf("0001-01-01", "0001-02-01", "0001-01-01", "0001-02-05"),
            ),
            Triple(
                MONTH,
                "9999-12-31",
                listOf("9999-12-01", "+10000-01-01", "9999-11-29", "+10000-01-03"),
            ),
        )
    for ((view, selected, expected) in cases) {
      val period = PlanningPeriod.of(view, day(selected))
      assertEquals(
          "$view of $selected",
          expected.map(::day),
          listOf(period.start, period.end, period.loadStart, period.loadEnd),
      )
    }
  }

  /**
   * A period holds its own days only: a month's grid loads days of other months it doesn't hold.
   */
  @Test
  fun containsOnlyItsOwnDays() {
    val october = PlanningPeriod.of(MONTH, day("2026-10-15"))
    val threeDays = PlanningPeriod.of(THREE_DAYS, day("2026-10-02"))
    val cases =
        listOf(
            Triple(october, "2026-10-01", true),
            Triple(october, "2026-10-31", true),
            Triple(october, "2026-09-30", false),
            Triple(october, "2026-11-01", false),
            Triple(threeDays, "2026-10-04", true),
            Triple(threeDays, "2026-10-01", false),
            Triple(threeDays, "2026-10-05", false),
        )
    for ((period, date, expected) in cases) {
      assertEquals("$date in $period", expected, day(date) in period)
    }
  }

  /** Previous and next move the period and keep the selected day's place in it. */
  @Test
  fun movesByOnePeriodKeepingTheSelectedDaysPlace() {
    // view, period start, selected, steps -> new start, new selected
    data class Case(
        val view: PlanningView,
        val start: String,
        val selected: String,
        val steps: Int,
        val newStart: String,
        val newSelected: String,
    )
    val cases =
        listOf(
            Case(WEEK, "2026-12-28", "2026-12-31", 1, "2027-01-04", "2027-01-07"),
            Case(LIST, "2027-01-04", "2027-01-07", -1, "2026-12-28", "2026-12-31"),
            // The window moves by three days, not to the selected day
            Case(THREE_DAYS, "2026-10-02", "2026-10-03", 1, "2026-10-05", "2026-10-06"),
            Case(THREE_DAYS, "2026-10-02", "2026-10-03", -1, "2026-09-29", "2026-09-30"),
            // A month keeps the day, clamped to the month's last day
            Case(MONTH, "2027-01-01", "2027-01-31", 1, "2027-02-01", "2027-02-28"),
            Case(MONTH, "2028-01-01", "2028-01-31", 1, "2028-02-01", "2028-02-29"),
            Case(MONTH, "2026-03-01", "2026-03-31", -1, "2026-02-01", "2026-02-28"),
            Case(MONTH, "2026-12-01", "2026-12-15", 1, "2027-01-01", "2027-01-15"),
            // At the ends of the supported years, the selected day stops on the first or last day
            Case(WEEK, "9999-12-20", "9999-12-26", 1, "9999-12-27", "9999-12-31"),
            Case(THREE_DAYS, "9999-12-28", "9999-12-30", 1, "9999-12-31", "9999-12-31"),
            // Three days back from early January 1 start on 1 January
            Case(THREE_DAYS, "0001-01-03", "0001-01-03", -1, "0001-01-01", "0001-01-01"),
            Case(THREE_DAYS, "0001-01-02", "0001-01-04", -1, "0001-01-01", "0001-01-01"),
        )
    for (case in cases) {
      val moved = PlanningPeriod(case.view, day(case.start)).moved(day(case.selected), case.steps)
      assertEquals(
          "$case",
          PlanningPeriod.Moved(
              PlanningPeriod(case.view, day(case.newStart)),
              day(case.newSelected),
          ),
          moved,
      )
    }
  }

  /** No step leaves years 1 to 9999: past the first or last period, the arrow stops. */
  @Test
  fun doesNotMovePastTheSupportedYears() {
    val cases =
        listOf(
            Triple(PlanningPeriod(WEEK, day("0001-01-01")), "0001-01-03", -1),
            Triple(PlanningPeriod(MONTH, day("0001-01-01")), "0001-01-15", -1),
            Triple(PlanningPeriod(THREE_DAYS, day("0001-01-01")), "0001-01-02", -1),
            Triple(PlanningPeriod(LIST, day("9999-12-27")), "9999-12-31", 1),
            Triple(PlanningPeriod(MONTH, day("9999-12-01")), "9999-12-01", 1),
            Triple(PlanningPeriod(THREE_DAYS, day("9999-12-29")), "9999-12-29", 1),
        )
    for ((period, selected, steps) in cases) {
      assertNull("$period from $selected by $steps", period.moved(day(selected), steps))
    }
  }

  /**
   * The loaded range is whole days in the display zone, so a 23- or 25-hour day stays one day, and
   * it ends with year 9999.
   */
  @Test
  fun rangeIsWholeDaysInTheDisplayZoneUntilYear9999() {
    val zurich = ZoneId.of("Europe/Zurich")
    val newYork = ZoneId.of("America/New_York")
    // period, zone -> first instant, end instant
    val cases =
        listOf(
            // Clocks go forward on Sunday 29 March 2026
            Triple(
                PlanningPeriod.of(WEEK, day("2026-03-29")) to zurich,
                "2026-03-22T23:00:00Z",
                "2026-03-29T22:00:00Z",
            ),
            // Clocks go back on Sunday 25 October 2026, inside the month's grid
            Triple(
                PlanningPeriod.of(MONTH, day("2026-10-15")) to zurich,
                "2026-09-27T22:00:00Z",
                "2026-11-01T23:00:00Z",
            ),
            // Another display zone, whose clocks go back on 1 November 2026
            Triple(
                PlanningPeriod.of(THREE_DAYS, day("2026-11-01")) to newYork,
                "2026-11-01T04:00:00Z",
                "2026-11-04T05:00:00Z",
            ),
            Triple(
                PlanningPeriod.of(MONTH, day("9999-12-31")) to zurich,
                "9999-11-28T23:00:00Z",
                "9999-12-31T23:00:00Z",
            ),
            Triple(
                PlanningPeriod.of(THREE_DAYS, day("9999-12-30")) to zurich,
                "9999-12-29T23:00:00Z",
                "9999-12-31T23:00:00Z",
            ),
        )
    for ((input, start, end) in cases) {
      val (period, zone) = input
      val range = period.range(zone)
      assertEquals("$period in $zone", zone, range.zoneId)
      assertEquals("$period in $zone", Instant.parse(start), range.startInclusive)
      assertEquals("$period in $zone", Instant.parse(end), range.endExclusive)
    }
  }

  /** A period can only start where its view starts, within years 1 to 9999. */
  @Test
  fun rejectsAStartItsViewCantHave() {
    val cases =
        listOf(
            LIST to "2026-09-29",
            WEEK to "2026-10-04",
            MONTH to "2026-10-02",
            THREE_DAYS to "0000-12-31",
            THREE_DAYS to "+10000-01-01",
        )
    for ((view, start) in cases) {
      assertThrows("$view from $start", IllegalArgumentException::class.java) {
        PlanningPeriod(view, day(start))
      }
    }
  }
}
