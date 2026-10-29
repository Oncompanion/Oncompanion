// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A clock that moves with a test's virtual time: `advanceTimeBy(2.minutes)` moves both the waiting
 * coroutines and the time this clock reads, so code that waits "until midnight" can be tested
 * without real delays.
 *
 * @param scheduler the test's scheduler (`testScheduler` in `runTest`)
 * @param start the time the clock reads at virtual time zero
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant,
    private val zone: ZoneId,
) : Clock() {

  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = VirtualClock(scheduler, start, zone)

  override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
}
