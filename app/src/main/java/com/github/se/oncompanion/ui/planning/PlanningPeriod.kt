// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.planning

import com.github.se.oncompanion.model.planning.PlanningRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The ways Planning shows its calendar (#555, #556). */
enum class PlanningView {
  /** The selected day's agenda under the strip of its week. */
  LIST,
  /** Three days side by side, from the selected day. */
  THREE_DAYS,
  /** The week, Monday to Sunday. */
  WEEK,
  /** The month, as a grid of whole weeks. */
  MONTH,
}

/**
 * The days a [view] covers, from [start] (included) to [end] (excluded), and the days it loads to
 * fill its layout, from [loadStart] to [loadEnd]: a month loads the whole weeks of its grid, so the
 * days of the months around it have their entries too.
 *
 * It only knows calendar days, never the entries, so it can be tested and reused on its own. A span
 * that isn't a view (e.g. a summary over chosen dates) builds its [PlanningRange] directly.
 *
 * @property start a Monday for [PlanningView.LIST] and [PlanningView.WEEK], the 1st for
 *   [PlanningView.MONTH], any day for [PlanningView.THREE_DAYS]; within years 1 to 9999
 */
data class PlanningPeriod(val view: PlanningView, val start: LocalDate) {
  init {
    require(PlanningDates.isSupported(start)) { "Period must start within years 1 to 9999" }
    require(start == alignedStart(view, start)) { "$view can't start on $start" }
  }

  /** The day after the period's last day. */
  val end: LocalDate
    get() = start.plusPeriods(view, 1)

  /** The first day to load: the Monday of a month's first week, else [start]. */
  val loadStart: LocalDate
    get() = if (view == PlanningView.MONTH) mondayOf(start) else start

  /** The day after the last day to load: the Monday after a month's last week, else [end]. */
  val loadEnd: LocalDate
    get() = if (view == PlanningView.MONTH) mondayOf(end.minusDays(1)).plusWeeks(1) else end

  /** Whether [date] is one of the period's own days (for a month, a day of that month). */
  operator fun contains(date: LocalDate): Boolean = date >= start && date < end

  /**
   * The days to load in [zoneId], as calendar days within years 1 to 9999: a grid that runs past 31
   * December 9999 stops there, and none starts before year 1 (1 January 1 is a Monday). These are
   * days in the display zone: a source whose store has its own limits (e.g. Firestore timestamps)
   * clamps the instants itself.
   */
  fun range(zoneId: ZoneId): PlanningRange =
      PlanningRange(loadStart, minOf(loadEnd, END_OF_LAST_DAY), zoneId)

  /**
   * The period [steps] periods away (-1 the previous one, 1 the next) with the day to select in it,
   * which keeps its place: [selected] moves by the same number of days, weeks or months (a month
   * keeps its day, clamped: 31 January becomes 28 February). At the ends of years 1 to 9999 the day
   * stops on the first or last day, and three days back from early January 1 start on 1 January.
   * Null when no period is left that way, so the screen can disable its arrow.
   */
  fun moved(selected: LocalDate, steps: Int): Moved? {
    var newStart = start.plusPeriods(view, steps)
    // Three days can start on any day, so they can stop on the first one
    if (view == PlanningView.THREE_DAYS && newStart < FIRST_DAY) newStart = FIRST_DAY
    if (newStart == start || !PlanningDates.isSupported(newStart)) return null
    val newSelected = selected.plusPeriods(view, steps).coerceIn(FIRST_DAY, LAST_DAY)
    return Moved(PlanningPeriod(view, newStart), newSelected)
  }

  /** The result of [moved]: the new period and the day selected in it. */
  data class Moved(val period: PlanningPeriod, val selectedDate: LocalDate)

  companion object {
    private val FIRST_DAY = LocalDate.of(1, 1, 1)
    private val LAST_DAY = LocalDate.of(9999, 12, 31)
    private val END_OF_LAST_DAY = LAST_DAY.plusDays(1)

    /** The period of [view] that holds [date]; three days start on [date] itself. */
    fun of(view: PlanningView, date: LocalDate): PlanningPeriod =
        PlanningPeriod(view, alignedStart(view, date))

    private fun alignedStart(view: PlanningView, date: LocalDate): LocalDate =
        when (view) {
          PlanningView.LIST,
          PlanningView.WEEK -> mondayOf(date)
          PlanningView.THREE_DAYS -> date
          PlanningView.MONTH -> date.withDayOfMonth(1)
        }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** [steps] lengths of [view]'s period later; a month clamps to the month's last day. */
    private fun LocalDate.plusPeriods(view: PlanningView, steps: Int): LocalDate =
        when (view) {
          PlanningView.LIST,
          PlanningView.WEEK -> plusWeeks(steps.toLong())
          PlanningView.THREE_DAYS -> plusDays(3L * steps)
          PlanningView.MONTH -> plusMonths(steps.toLong())
        }
  }
}
