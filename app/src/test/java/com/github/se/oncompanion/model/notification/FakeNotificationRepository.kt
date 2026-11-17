// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [NotificationRepository] for ViewModel unit tests (no Firebase).
 *
 * Follows the contract of [NotificationRepository]:
 * - [notify] requires [CircleNotification.isValid] (else [IllegalArgumentException]), stores it
 *   unseen with `createdAt = clock()`, and ignores a notification already sent.
 * - [observeRecent] emits the notifications of the last [CircleNotification.KEPT_FOR] before
 *   [clock], newest first, then every change.
 * - [markSeen] marks the given IDs seen and ignores those that don't exist.
 *
 * Test hook: [deliver] stores a notification exactly as given (e.g. old or already seen), replacing
 * one with the same ID.
 *
 * @param clock the current time: the 7-day window and the time a notification is sent
 */
class FakeNotificationRepository(private val clock: () -> Instant = Instant::now) :
    NotificationRepository {

  // uid -> notification ID -> notification
  private val store = MutableStateFlow<Map<String, Map<String, CircleNotification>>>(emptyMap())

  /** Stores [notification] for [uid] as given. */
  fun deliver(uid: String, notification: CircleNotification) {
    store.update { it + (uid to (it[uid].orEmpty() + (notification.id to notification))) }
  }

  override suspend fun notify(recipientUid: String, notification: CircleNotification) {
    require(notification.isValid()) { "Invalid notification" }
    if (store.value[recipientUid]?.containsKey(notification.id) == true) return
    deliver(recipientUid, notification.copy(createdAt = clock(), seen = false))
  }

  override fun observeRecent(uid: String): Flow<List<CircleNotification>> = flow {
    val since = clock().minus(CircleNotification.KEPT_FOR)
    emitAll(
        store.map { all ->
          all[uid]
              .orEmpty()
              .values
              .filter { it.createdAt > since }
              .sortedByDescending { it.createdAt }
        }
    )
  }

  override suspend fun markSeen(uid: String, ids: Collection<String>) {
    store.update { all ->
      val mine = all[uid] ?: return@update all
      all + (uid to mine.mapValues { (id, n) -> if (id in ids) n.copy(seen = true) else n })
    }
  }
}
