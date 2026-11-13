// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * One day of the "This week" chart.
 *
 * @property intensity the highest intensity logged that day, `null` if none of its entries gave one
 * @property hasEntries whether something was logged that day: without an [intensity] the chart
 *   shows an open dot, so a day logged without a number isn't mistaken for a day without symptoms
 */
data class WeekDay(val date: LocalDate, val intensity: Int?, val hasEntries: Boolean)

/**
 * The calendar week of the journal's "This week" card (Figma: "Symptoms – History").
 *
 * @property days Monday to Sunday; the days after today are empty
 * @property entryCount how many symptoms were logged this week
 * @property mostOften the symptom logged most often this week, ties going to the one listed first
 *   in [SymptomType]
 */
data class WeekSummary(
    val days: List<WeekDay>,
    val entryCount: Int,
    val mostOften: SymptomType,
)

/**
 * Sums up the calendar week (Monday to Sunday) that contains [today], from the journal's [days].
 * Returns `null` when nothing was logged this week, so the card isn't shown empty. Only what the
 * user entered is counted: no score, no trend.
 */
fun weekSummaryOf(days: List<JournalDay>, today: LocalDate): WeekSummary? {
  val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
  val thisWeek = days.filter { it.date >= monday && it.date <= monday.plusDays(6) }
  val entries = thisWeek.flatMap { it.entries }
  if (entries.isEmpty()) return null

  val weekDays =
      (0L..6L).map { offset ->
        val date = monday.plusDays(offset)
        val dayEntries = thisWeek.filter { it.date == date }.flatMap { it.entries }
        WeekDay(
            date = date,
            intensity = dayEntries.mapNotNull { it.entry.intensity }.maxOrNull(),
            hasEntries = dayEntries.isNotEmpty(),
        )
      }
  val counts = entries.flatMap { it.entry.types }.groupingBy { it }.eachCount()
  val mostOften =
      counts.entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).first().key
  return WeekSummary(weekDays, entries.size, mostOften)
}
