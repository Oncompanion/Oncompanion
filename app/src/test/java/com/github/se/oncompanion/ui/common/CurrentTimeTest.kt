// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import com.github.se.oncompanion.ui.VirtualClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentTimeTest {

  private val zurich = ZoneId.of("Europe/Zurich")

  /** Collects [currentTime] in the background from [start] in Zurich, and returns the times. */
  private fun TestScope.timesFrom(start: LocalDateTime): List<Instant> {
    val clock = VirtualClock(testScheduler, start.atZone(zurich).toInstant(), zurich)
    val times = mutableListOf<Instant>()
    backgroundScope.launch { currentTime(clock).collect { times += it } }
    runCurrent()
    return times
  }

  private fun instant(time: LocalDateTime) = time.atZone(zurich).toInstant()

  @Test
  fun givesTheTimeRightAway_thenTheStartOfTheNextMinute() = runTest {
    val start = LocalDateTime.of(2026, 10, 20, 14, 20, 30)
    val times = timesFrom(start)
    assertEquals(listOf(instant(start)), times)

    // Not a millisecond before the minute
    advanceTimeBy(29.seconds + 999.milliseconds)
    runCurrent()
    assertEquals(1, times.size)

    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertEquals(listOf(instant(start), instant(start.withSecond(0).plusMinutes(1))), times)
  }

  @Test
  fun startingOnTheMinute_ticksAMinuteLater() = runTest {
    val start = LocalDateTime.of(2026, 10, 20, 14, 20)
    val times = timesFrom(start)

    advanceTimeBy(59.seconds)
    runCurrent()
    assertEquals(1, times.size)

    advanceTimeBy(1.seconds)
    runCurrent()
    assertEquals(instant(start.plusMinutes(1)), times.last())
  }

  @Test
  fun ticksEveryMinute_throughTheNightTheClocksGoBack() = runTest {
    // At 3:00 on 25 October 2026 the clocks go back to 2:00 in Zurich: 2:00 to 3:00 happens twice
    val times = timesFrom(LocalDateTime.of(2026, 10, 25, 1, 0))

    advanceTimeBy(3.hours)
    runCurrent()

    // Every minute of the 3 real hours, none skipped or repeated
    assertEquals(181, times.size)
    assertTrue(
        times.zipWithNext().all { (a, b) -> Duration.between(a, b) == Duration.ofMinutes(1) }
    )
  }
}
