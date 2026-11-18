// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transformLatest

/**
 * Keeps only the notifications younger than [CircleNotification.KEPT_FOR], and removes each one the
 * moment it turns that old, while the flow is collected. The database sends nothing when a
 * notification ages: without this, an open feed would keep it until Firestore's TTL deletes it, and
 * offline even longer.
 *
 * @param clock the current time
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<List<CircleNotification>>.keepRecent(
    clock: () -> Instant
): Flow<List<CircleNotification>> = transformLatest { all ->
  // Emit what is recent now, then wait until the next one ages; a new list restarts this
  while (true) {
    val now = clock()
    val recent = all.filter { it.expiry > now }
    emit(recent)
    val nextExpiry = recent.minOfOrNull { it.expiry } ?: break
    delay(Duration.between(now, nextExpiry).toMillis().coerceAtLeast(1))
  }
}

/** When this notification leaves the feed. */
private val CircleNotification.expiry: Instant
  get() = createdAt.plus(CircleNotification.KEPT_FOR)
