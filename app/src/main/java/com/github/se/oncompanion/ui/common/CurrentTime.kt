// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The time now, then again at the start of each new minute, so a screen left open shows what is
 * happening now and what is over without being reopened. Like [currentDay], the flow sleeps until
 * the next minute instead of checking the time.
 *
 * It ticks on the minute in every time zone in use today, so a clock change never skips or repeats
 * a tick: the instant goes on, only its local time jumps.
 *
 * @param clock the current time
 */
fun currentTime(clock: Clock): Flow<Instant> = flow {
  while (true) {
    val now = clock.instant()
    emit(now)
    val nextMinute = now.truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES)
    // delay() rounds up to the next millisecond, so it never wakes before the minute
    delay(Duration.between(now, nextMinute).toNanos().nanoseconds)
  }
}
