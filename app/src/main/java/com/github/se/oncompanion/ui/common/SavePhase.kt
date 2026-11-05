// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

/**
 * Where a form is in saving what the user typed. One value instead of separate "saving" and "saved"
 * flags, so a form can never be both at once. Every form uses it, so they all follow the same
 * pattern. A failed save goes back to [EDITING]; the form keeps its own error to explain why.
 */
enum class SavePhase {
  /** The user can change the fields and save. */
  EDITING,
  /** The save was started and hasn't finished: the fields are locked and a spinner shows. */
  SAVING,
  /** The save is done: the fields stay locked while the screen leaves. */
  SAVED,
}
