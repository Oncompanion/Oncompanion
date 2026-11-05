// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Counts the writes that the server rejected after they were saved on the device, so the app can
 * tell the user that a change was undone.
 *
 * Repository writes return as soon as they are saved on the device, so the screen that made a
 * change is often gone when the server answers. If the server rejects the write (security rules,
 * revoked access), Firestore undoes it on the device: repositories then [report] it here, and one
 * app-wide snackbar shows it.
 */
class RejectedWrites {
  private val count = MutableStateFlow(0)

  /** How many rejected writes the user hasn't been told about yet. */
  val unseen: StateFlow<Int> = count.asStateFlow()

  /** Records that the server rejected a write that was already saved on the device. */
  fun report() {
    count.update { it + 1 }
  }

  /**
   * Records that the user was told about [seen] rejected writes. Rejections reported since stay
   * [unseen], so a rejection that arrives while the message is showing isn't lost.
   */
  fun acknowledge(seen: Int) {
    count.update { (it - seen).coerceAtLeast(0) }
  }

  /** Forgets every rejection, at sign-out, so the next user isn't told about someone else's. */
  fun forget() {
    count.value = 0
  }
}

/** Holds the app's [RejectedWrites]. Tests replace [rejectedWrites] with their own instance. */
object RejectedWritesProvider {
  /** Shared by every repository that writes to Firestore and by the snackbar that shows it. */
  @Volatile var rejectedWrites: RejectedWrites = RejectedWrites()
}
