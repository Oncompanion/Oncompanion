// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.ui.VirtualClock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentDayTest {

  private val zurich = ZoneId.of("Europe/Zurich")

  /** Collects [currentDay] in the background from [start], and returns the dates received. */
  private fun TestScope.daysFrom(start: LocalDateTime, zone: ZoneId = zurich): List<LocalDate> {
    val clock = VirtualClock(testScheduler, start.atZone(zone).toInstant(), zone)
    val days = mutableListOf<LocalDate>()
    backgroundScope.launch { currentDay(clock).collect { days += it } }
    runCurrent()
    return days
  }

  @Test
  fun givesTodayRightAway() = runTest {
    val days = daysFrom(LocalDateTime.of(2026, 10, 27, 14, 0))

    assertEquals(listOf(LocalDate.of(2026, 10, 27)), days)
  }

  @Test
  fun givesTheNewDayAtMidnight() = runTest {
    val days = daysFrom(LocalDateTime.of(2026, 10, 27, 23, 58))

    // 23:59: still the same day
    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(listOf(LocalDate.of(2026, 10, 27)), days)

    // 00:00: the new day arrives
    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28)), days)
  }

  @Test
  fun keepsGoingNightAfterNight() = runTest {
    val days = daysFrom(LocalDateTime.of(2026, 10, 27, 12, 0))

    advanceTimeBy(48.hours)
    runCurrent()

    assertEquals(
        listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 29)),
        days,
    )
  }

  @Test
  fun waitsForMidnightOnTheNightTheClocksGoBack() = runTest {
    // Sunday 25 October 2026 lasts 25 hours in Zurich: the clocks go back at 3:00
    val days = daysFrom(LocalDateTime.of(2026, 10, 25, 0, 0))

    // After 24 hours, it is only 23:00 on the 25th
    advanceTimeBy(24.hours)
    runCurrent()
    assertEquals(listOf(LocalDate.of(2026, 10, 25)), days)

    // After 25 hours, it is midnight
    advanceTimeBy(1.hours)
    runCurrent()
    assertEquals(listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)), days)
  }

  @Test
  fun followsTheClocksTimeZone() = runTest {
    // 23:30 in Zurich is already the next day in Tokyo
    val tokyo = ZoneId.of("Asia/Tokyo")
    val start = LocalDateTime.of(2026, 10, 27, 23, 30).atZone(zurich)
    val days = daysFrom(start.withZoneSameInstant(tokyo).toLocalDateTime(), tokyo)

    assertEquals(listOf(LocalDate.of(2026, 10, 28)), days)
  }
}
