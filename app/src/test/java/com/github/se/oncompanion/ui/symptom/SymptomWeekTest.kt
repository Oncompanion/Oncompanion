// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SymptomWeekTest {

  /** Wednesday 7 October 2026: its week runs from Monday 5 to Sunday 11 October. */
  private val today = LocalDate.of(2026, 10, 7)

  private fun entry(vararg types: SymptomType, intensity: Int? = null) =
      JournalEntry(
          SymptomEntry(types = types.toList(), intensity = intensity, occurredAt = Instant.EPOCH),
          LocalTime.NOON,
      )

  private fun day(date: String, vararg entries: JournalEntry) =
      JournalDay(LocalDate.parse(date), entries.toList())

  @Test
  fun nothingLoggedThisWeek_hasNoSummary() {
    val cases =
        listOf(
            emptyList(),
            // Sunday before and Monday after the week
            listOf(day("2026-10-04", entry(SymptomType.PAIN))),
            listOf(day("2026-10-12", entry(SymptomType.PAIN))),
        )
    for (days in cases) assertNull("$days", weekSummaryOf(days, today))
  }

  @Test
  fun theWeek_runsFromMondayToSunday_whateverTheDayOfToday() {
    // Monday and Sunday are the first and last days of the week they belong to
    for ((now, monday) in
        listOf(
            "2026-10-05" to "2026-10-05",
            "2026-10-11" to "2026-10-05",
            "2026-10-12" to "2026-10-12",
        )) {
      val summary = weekSummaryOf(listOf(day(now, entry(SymptomType.PAIN))), LocalDate.parse(now))!!
      assertEquals(LocalDate.parse(monday), summary.days.first().date)
      assertEquals(LocalDate.parse(monday).plusDays(6), summary.days.last().date)
      assertEquals(7, summary.days.size)
    }
  }

  @Test
  fun eachDay_showsItsHighestIntensity_orWhetherSomethingWasLogged() {
    val summary =
        weekSummaryOf(
            listOf(
                // Newest first, as in the journal
                day("2026-10-07", entry(SymptomType.PAIN)),
                day(
                    "2026-10-06",
                    entry(SymptomType.FATIGUE, intensity = 3),
                    entry(SymptomType.NAUSEA, intensity = 8),
                    entry(SymptomType.FEVER),
                ),
                // Last week: not counted
                day("2026-10-02", entry(SymptomType.FEVER, intensity = 10)),
            ),
            today,
        )!!

    val byDate = summary.days.associateBy { it.date.toString() }
    assertEquals(WeekDay(LocalDate.parse("2026-10-05"), null, false), byDate["2026-10-05"])
    assertEquals(WeekDay(LocalDate.parse("2026-10-06"), 8, true), byDate["2026-10-06"])
    assertEquals(WeekDay(LocalDate.parse("2026-10-07"), null, true), byDate["2026-10-07"])
    assertEquals(WeekDay(LocalDate.parse("2026-10-08"), null, false), byDate["2026-10-08"])
    assertEquals(4, summary.entryCount)
  }

  @Test
  fun mostOften_isTheSymptomLoggedTheMost_tiesGoingToTheFirstListed() {
    val cases =
        listOf(
            listOf(entry(SymptomType.PAIN), entry(SymptomType.FATIGUE, SymptomType.PAIN)) to
                SymptomType.PAIN,
            listOf(entry(SymptomType.NAUSEA), entry(SymptomType.FATIGUE)) to SymptomType.FATIGUE,
            listOf(entry(SymptomType.OTHER)) to SymptomType.OTHER,
        )
    for ((entries, expected) in cases) {
      val summary = weekSummaryOf(listOf(JournalDay(today, entries)), today)!!
      assertEquals(expected, summary.mostOften)
      assertEquals(entries.size, summary.entryCount)
    }
  }
}
