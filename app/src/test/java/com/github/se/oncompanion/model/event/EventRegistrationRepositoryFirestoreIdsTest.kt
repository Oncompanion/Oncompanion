// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Checks that [EventRegistrationRepositoryFirestore] rejects ids that can't be document ids before
 * reaching Firestore. Reading and writing are tested on the emulator.
 */
class EventRegistrationRepositoryFirestoreIdsTest {

  // Fails the test if Firestore is reached
  private val repository =
      EventRegistrationRepositoryFirestore(dbProvider = { error("Firestore must not be reached") })

  @Test
  fun invalidIds_areRejected_withoutReachingFirestore() {
    for ((uid, eventId) in listOf("" to "yoga", "sam" to " ", "a/b" to "yoga", "sam" to "a/b")) {
      assertThrows(IllegalArgumentException::class.java) {
        repository.observeIsRegistered(uid, eventId)
      }
      assertThrows(IllegalArgumentException::class.java) { repository.confirm(uid, eventId) }
      assertThrows(IllegalArgumentException::class.java) { repository.remove(uid, eventId) }
    }
  }
}
