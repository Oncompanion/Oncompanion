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
 * a prescription), so [DeleteConfirmation] can run the same deletion for every such screen.
 *
 * @property canDelete the item is shown and can be deleted
 * @property isConfirmingDelete the user asked to delete the item and hasn't confirmed or cancelled
 * @property isDeleting the item is being deleted
 * @property hasDeleteError the last deletion failed; the item is kept
 * @property isDeleted the item is deleted: the screen is done
 */
interface DeletableUiState<S : DeletableUiState<S>> {
  val canDelete: Boolean
  val isConfirmingDelete: Boolean
  val isDeleting: Boolean
  val hasDeleteError: Boolean
  val isDeleted: Boolean

  /** A copy of this state with the given delete fields; the others stay the same. */
  fun withDelete(
      isConfirmingDelete: Boolean = this.isConfirmingDelete,
      isDeleting: Boolean = this.isDeleting,
      hasDeleteError: Boolean = this.hasDeleteError,
      isDeleted: Boolean = this.isDeleted,
  ): S

  /**
   * The dialog asking to confirm the deletion is shown: only while its Delete button can do
   * something, so it never stays over an item that is gone or reloading.
   */
  val isDeleteDialogShown: Boolean
    get() = isConfirmingDelete && (canDelete || isDeleting)

  /**
   * This state once the item has been read again, [exists] or not: an item deleted elsewhere can't
   * be deleted here, so its confirmation and its error are dropped.
   */
  fun afterRead(exists: Boolean): S {
    val stillConfirming = isConfirmingDelete && exists
    return withDelete(
        isConfirmingDelete = stillConfirming,
        hasDeleteError = hasDeleteError && stillConfirming,
    )
  }
}

/**
 * Asks to confirm the deletion of a detail screen's item, then deletes it, updating [uiState].
 * Shared by the detail ViewModels so that every deletion behaves the same.
 *
 * @param scope where the deletion runs, the ViewModel's: leaving the screen cancels it
 * @param tag the ViewModel's log tag
 * @param item what is deleted, for the log (e.g. "the symptom s1")
 */
class DeleteConfirmation<S : DeletableUiState<S>>(
    private val uiState: MutableStateFlow<S>,
    private val scope: CoroutineScope,
    private val tag: String,
    private val item: String,
) {

  /** Asks the user to confirm the deletion. Nothing is deleted before [confirm]. */
  fun request() {
    uiState.update {
      if (it.canDelete) it.withDelete(isConfirmingDelete = true, hasDeleteError = false) else it
    }
  }

  /** Closes the confirmation and keeps the item. Ignored while it is being deleted. */
  fun cancel() {
    uiState.update {
      if (it.isDeleting) it else it.withDelete(isConfirmingDelete = false, hasDeleteError = false)
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
    if (!state.isConfirmingDelete || !state.canDelete) return
    val uid = signedInUid()
    if (uid == null) {
      uiState.update { it.withDelete(hasDeleteError = true) }
      return
    }
    stopObserving()
    uiState.update { it.withDelete(isDeleting = true, hasDeleteError = false) }
    scope.launch {
      try {
        delete(uid)
        uiState.update {
          it.withDelete(isDeleting = false, isConfirmingDelete = false, isDeleted = true)
        }
      } catch (e: CancellationException) {
        // Not a failure: the screen was left, the deletion stops with it
        throw e
      } catch (e: Exception) {
        Log.e(tag, "Failed to delete $item", e)
        uiState.update { it.withDelete(isDeleting = false, hasDeleteError = true) }
        onFailure(uid)
      }
    }
  }
}
