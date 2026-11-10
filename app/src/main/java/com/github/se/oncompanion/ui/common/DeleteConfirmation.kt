// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The UI state of a detail screen whose item can be deleted after a confirmation (e.g. a symptom or
 * a prescription): what [DeleteConfirmation] reads before each step.
 *
 * @property delete where the deletion is at
 * @property canDelete the item is shown and can be deleted; the screen also shows its Delete button
 *   from it, so both always agree
 */
interface HasDeletePhase {
  val delete: DeletePhase
  val canDelete: Boolean

  /**
   * The dialog asking to confirm the deletion is shown: only while its Delete button can do
   * something, so it never stays over an item that is gone or reloading.
   */
  val isDeleteDialogShown: Boolean
    get() =
        when (delete) {
          DeletePhase.CONFIRMING,
          DeletePhase.FAILED -> canDelete
          DeletePhase.DELETING -> true
          DeletePhase.IDLE,
          DeletePhase.DELETED -> false
        }
}

/**
 * Asks to confirm the deletion of a detail screen's item, then deletes it, updating [uiState].
 * Shared by the detail ViewModels so that every deletion behaves the same.
 *
 * @param scope where the deletion runs, the ViewModel's: leaving the screen cancels it
 * @param tag the ViewModel's log tag
 * @param item what is deleted, for the log (e.g. "the symptom s1")
 * @param withDelete a copy of the screen's state with the given deletion, e.g. `{ state, delete ->
 *   state.copy(delete = delete) }`: each state class has its own `copy`, so it is passed in
 */
class DeleteConfirmation<S : HasDeletePhase>(
    private val uiState: MutableStateFlow<S>,
    private val scope: CoroutineScope,
    private val tag: String,
    private val item: String,
    private val withDelete: (S, DeletePhase) -> S,
) {

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirm]. */
  fun request() {
    uiState.update { if (it.canDelete) withDelete(it, DeletePhase.CONFIRMING) else it }
  }

  /** Closes the confirmation and keeps the item. Ignored while it is being deleted. */
  fun cancel() {
    uiState.update {
      // Once deleting, the deletion goes on; once deleted, the screen is leaving
      if (it.delete == DeletePhase.DELETING || it.delete == DeletePhase.DELETED) it
      else withDelete(it, DeletePhase.IDLE)
    }
  }

  /**
   * Deletes the item once the user confirmed. A failure keeps the item and the dialog with an
   * error, so the user can try again.
   *
   * @param signedInUid the user the item belongs to; `null` if nobody is signed in
   * @param stopObserving stops reading the item first, or it would show as missing before the
   *   screen closes
   * @param delete deletes the item of the given user
   * @param onFailure resumes reading the item of the given user after a failed deletion
   */
  fun confirm(
      signedInUid: () -> String?,
      stopObserving: () -> Unit,
      delete: suspend (uid: String) -> Unit,
      onFailure: (uid: String) -> Unit,
  ) {
    val state = uiState.value
    val asked = state.delete == DeletePhase.CONFIRMING || state.delete == DeletePhase.FAILED
    if (!asked || !state.canDelete) return
    val uid = signedInUid()
    if (uid == null) {
      uiState.update { withDelete(it, DeletePhase.FAILED) }
      return
    }
    stopObserving()
    uiState.update { withDelete(it, DeletePhase.DELETING) }
    scope.launch {
      try {
        delete(uid)
        uiState.update { withDelete(it, DeletePhase.DELETED) }
      } catch (e: CancellationException) {
        // Not a failure: the screen was left, the deletion stops with it
        throw e
      } catch (e: Exception) {
        Log.e(tag, "Failed to delete $item", e)
        uiState.update { withDelete(it, DeletePhase.FAILED) }
        onFailure(uid)
      }
    }
  }
}
