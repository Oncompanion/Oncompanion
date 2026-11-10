// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

/**
 * Where the deletion of a detail screen's item is. One value instead of separate flags, so a screen
 * can never be, say, deleting and deleted at once. Every detail screen that deletes its item uses
 * it, so they all follow the same pattern.
 */
enum class DeletePhase {
  /** Nothing asked: the item is shown. */
  IDLE,
  /** The dialog asks the user to confirm the deletion. */
  CONFIRMING,
  /** The last deletion failed: the dialog stays open with an error, and the item is kept. */
  FAILED,
  /** The user confirmed and the item is being deleted: the dialog shows a spinner. */
  DELETING,
  /** The item is deleted: the screen leaves. */
  DELETED;

  /**
   * This phase once the item has been read again, [exists] or not: an item deleted elsewhere can't
   * be deleted here, so its confirmation and its error are dropped. A deletion under way or done
   * goes on.
   */
  fun afterRead(exists: Boolean): DeletePhase =
      if (!exists && (this == CONFIRMING || this == FAILED)) IDLE else this
}
