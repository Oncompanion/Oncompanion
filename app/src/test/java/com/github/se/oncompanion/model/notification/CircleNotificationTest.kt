// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import com.github.se.oncompanion.model.carecircle.CarePermission
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests what [CircleNotification] derives from what the server stored. */
class CircleNotificationTest {

  private fun added(itemId: String, seen: Boolean = false) =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = itemId,
          actorUid = "bob-uid",
          actorFirstName = "Bob",
          createdAt = Instant.parse("2026-11-17T09:00:00Z"),
          seen = seen,
      )

  /** One addition or decision has one ID, so the server can't store it twice. */
  @Test
  fun itsIdIsTheKindAndTheItem() {
    assertEquals("PRESCRIPTION_ADDED_presc-1", added("presc-1").id)
  }

  /** The section isn't stored: it follows from the kind, so the two never disagree. */
  @Test
  fun aPrescriptionNotificationBelongsToPrescriptions() {
    assertEquals(CarePermission.PRESCRIPTIONS, added("presc-1").section)
  }

  /** The app refuses what the rules would: a blank or too long name, a blank item. */
  @Test
  fun isValid_mirrorsTheRules() {
    val cases =
        listOf(
            added("presc-1") to true,
            added("presc-1").copy(actorFirstName = "a".repeat(50)) to true,
            added("presc-1").copy(actorFirstName = " ") to false,
            added("presc-1").copy(actorFirstName = "a".repeat(51)) to false,
            added(" ") to false,
            added("presc-1").copy(actorUid = "") to false,
        )
    for ((notification, expected) in cases) {
      assertEquals(notification.toString(), expected, notification.isValid())
    }
  }

  /** The counts on the tabs: only unseen notifications, and no entry for a section with none. */
  @Test
  fun unseenCountBySection_countsOnlyUnseenOnes() {
    val cases =
        listOf<Pair<List<CircleNotification>, Map<CarePermission, Int>>>(
            emptyList<CircleNotification>() to emptyMap(),
            listOf(added("a", seen = true)) to emptyMap(),
            listOf(added("a"), added("b", seen = true), added("c")) to
                mapOf(CarePermission.PRESCRIPTIONS to 2),
        )
    for ((notifications, expected) in cases) {
      assertEquals(expected, notifications.unseenCountBySection())
    }
  }
}
