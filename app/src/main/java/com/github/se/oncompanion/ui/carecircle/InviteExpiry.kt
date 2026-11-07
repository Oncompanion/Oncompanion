// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Calls [show] with the current time, then again each time one of [expiries] passes, so an
 * invitation never looks valid after it has expired while a screen is open. Returns once no expiry
 * is left to wait for. Callers run it in `collectLatest`, so new data cancels the wait and starts
 * again from it.
 *
 * The timer doesn't run while the phone sleeps, and the user may change the clock: screens also
 * check again when they resume.
 *
 * @param expiries when each invitation shown expires; null for one that never does (not on the
 *   server yet)
 */
suspend fun repeatAtEachExpiry(
    clock: Clock,
    expiries: Collection<Instant?>,
    show: (now: Instant) -> Unit,
) {
  while (true) {
    val now = clock.instant()
    show(now)
    val nextExpiry = expiries.filterNotNull().filter { it > now }.minOrNull() ?: return
    // Rounded up, so it wakes up at or after the expiry, never just before
    delay(Duration.between(now, nextExpiry).toMillis() + 1)
  }
}
