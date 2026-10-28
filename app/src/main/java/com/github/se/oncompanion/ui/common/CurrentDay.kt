// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Today's date, then the new date at each midnight, so a screen left open overnight moves on to the
 * new day by itself. Waiting costs nothing: the flow sleeps until midnight instead of checking the
 * time.
 *
 * Midnight is computed again each day from the clock, so a 23- or 25-hour day (when the clocks
 * change) still ends at midnight.
 *
 * @param clock the current time, in the time zone the day is shown in
 */
fun currentDay(clock: Clock): Flow<LocalDate> = flow {
  while (true) {
    val now = clock.instant()
    val today = now.atZone(clock.zone).toLocalDate()
    emit(today)
    val nextMidnight = today.plusDays(1).atStartOfDay(clock.zone).toInstant()
    delay(Duration.between(now, nextMidnight).toMillis().milliseconds)
  }
}
