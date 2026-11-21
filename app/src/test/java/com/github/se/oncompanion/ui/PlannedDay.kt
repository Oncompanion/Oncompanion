// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * A fixed day for tests that show screens starting from today (Overview, Planning, a new
 * appointment), passed to `AppNavHost` as its clock. A test reading the real date would fail when
 * its run crosses midnight, or the end of a week, between seeding its data and the screen opening.
 */
object PlannedDay {
  /**
   * A Wednesday, so the day before and after are in the same week. Far ahead, so screens that still
   * read the real clock (e.g. the event page) see everything on it as upcoming.
   */
  val today: LocalDate = LocalDate.of(2099, 6, 3)

  /** Noon on [today] in the phone's time zone. */
  val clock: Clock =
      Clock.fixed(
          today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
          ZoneId.systemDefault(),
      )
}
