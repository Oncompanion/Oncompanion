// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.domain.planning.DEFAULT_PLANNING_DURATION
import com.github.se.oncompanion.model.planning.PlanningTiming
import java.time.Instant
import java.time.ZoneId

/** Where an appointment or event stands at the current time, as its row in Planning shows it. */
enum class PlanningStatus {
  /** It starts on a later day. */
  UPCOMING,

  /** It starts later today ("In 2 h"). */
  TODAY,

  /** It has started and isn't over yet, even if it started on an earlier day. */
  HAPPENING_NOW,

  /** It is over. */
  PAST,
}

/**
 * Where this appointment or event stands at [now], "today" being the day of [now] in [zoneId], the
 * phone's time zone Planning is shown in. It is over at its end; without one (a hand-built entry:
 * the Planning sources always give one), it lasts [DEFAULT_PLANNING_DURATION], as on the Overview.
 */
fun PlanningTiming.Timed.statusAt(now: Instant, zoneId: ZoneId): PlanningStatus =
    when {
      now >= (end ?: instant + DEFAULT_PLANNING_DURATION) -> PlanningStatus.PAST
      now >= instant -> PlanningStatus.HAPPENING_NOW
      dateIn(zoneId) == now.atZone(zoneId).toLocalDate() -> PlanningStatus.TODAY
      else -> PlanningStatus.UPCOMING
    }
