// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.session

/**
 * For the fake repositories: a server that rejects writes after they returned, like Firestore when
 * the rules refuse a change that was already saved on the device.
 *
 * While [rejectsWrites] is true, the fake calls [saving] before each write and still applies it, so
 * the write returns as usual. [reject] then undoes every such write, by restoring what the fake
 * stored before the first one, and reports each to [rejectedWrites].
 *
 * @param S what the fake stores
 */
class RejectingServer<S>(private val rejectedWrites: RejectedWrites) {

  /** Whether the next writes will be rejected by [reject]. */
  var rejectsWrites = false

  private var beforeRejectedWrites: S? = null
  private var pending = 0

  /** Called by the fake with what it stores, right before it applies a write. */
  fun saving(stored: S) {
    if (!rejectsWrites) return
    if (pending++ == 0) beforeRejectedWrites = stored
  }

  /** Undoes the writes saved since [rejectsWrites] was set, by passing [restore] the old store. */
  @Suppress("UNCHECKED_CAST")
  fun reject(restore: (S) -> Unit) {
    if (pending == 0) return
    restore(beforeRejectedWrites as S)
    repeat(pending) { rejectedWrites.report() }
    pending = 0
    beforeRejectedWrites = null
  }
}
