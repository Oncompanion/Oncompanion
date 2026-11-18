// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [keepRecent]: while the feed is observed, each notification leaves it the moment it turns 7
 * days old, without the database sending anything new (e.g. offline, or before Firestore's TTL).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentNotificationsTest {

  private val start = Instant.parse("2026-11-18T09:00:00Z")

  private fun TestScope.clock(): () -> Instant = { start.plusMillis(testScheduler.currentTime) }

  private fun added(itemId: String, age: Duration) =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = itemId,
          actorUid = "bob-uid",
          actorFirstName = "Bob",
          createdAt = start.minus(age),
      )

  /** Collects what [keepRecent] shows of [source] into a list, in the background. */
  private fun TestScope.shown(
      source: MutableStateFlow<List<CircleNotification>>
  ): MutableList<List<String>> {
    val emitted = mutableListOf<List<String>>()
    backgroundScope.launch {
      source.keepRecent(clock()).collect { list -> emitted += list.map { it.itemId } }
    }
    runCurrent()
    return emitted
  }

  @Test
  fun aNotificationLeaves_theMomentItTurnsSevenDaysOld_withoutANewSnapshot() = runTest {
    val emitted = shown(MutableStateFlow(listOf(added("presc-1", age = Duration.ofDays(6)))))
    assertEquals(listOf("presc-1"), emitted.last())

    // One minute before its 7 days, it's still there
    advanceTimeBy(Duration.ofDays(1).minusMinutes(1).toMillis())
    runCurrent()
    assertEquals(listOf("presc-1"), emitted.last())

    advanceTimeBy(Duration.ofMinutes(2).toMillis())
    runCurrent()
    assertEquals(emptyList<String>(), emitted.last())
  }

  @Test
  fun eachLeavesAtItsOwnTime_andAlreadyOldOnesNeverShow() = runTest {
    val emitted =
        shown(
            MutableStateFlow(
                listOf(
                    added("one-day", age = Duration.ofDays(1)),
                    added("six-days", age = Duration.ofDays(6)),
                    added("eight-days", age = Duration.ofDays(8)),
                )
            )
        )
    assertEquals(listOf("one-day", "six-days"), emitted.last())

    advanceTimeBy(Duration.ofDays(2).toMillis())
    runCurrent()
    assertEquals(listOf("one-day"), emitted.last())

    advanceTimeBy(Duration.ofDays(5).toMillis())
    runCurrent()
    assertEquals(emptyList<String>(), emitted.last())
  }

  @Test
  fun aNewSnapshot_isShownAtOnce_andStillExpiresOnTime() = runTest {
    val source = MutableStateFlow(listOf(added("old", age = Duration.ofDays(6))))
    val emitted = shown(source)

    advanceTimeBy(Duration.ofHours(12).toMillis())
    source.value = source.value + added("new", age = Duration.ZERO)
    runCurrent()
    assertEquals(listOf("old", "new"), emitted.last())

    advanceTimeBy(Duration.ofHours(13).toMillis())
    runCurrent()
    assertEquals(listOf("new"), emitted.last())
  }
}
