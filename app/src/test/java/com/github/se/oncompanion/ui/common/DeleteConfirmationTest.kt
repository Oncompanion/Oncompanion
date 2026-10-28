// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides android.util.Log, used when a deletion fails
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteConfirmationTest {

  /** The smallest detail screen state: an item that is shown or not. */
  private data class ItemState(
      val isShown: Boolean = true,
      override val delete: DeleteState = DeleteState(),
  ) : HasDeleteState {
    override val canDelete: Boolean
      get() = isShown && !delete.isDeleting && !delete.isDeleted
  }

  private val uiState = MutableStateFlow(ItemState())
  private val deleted = mutableListOf<String>()
  private val events = mutableListOf<String>()

  private fun TestScope.deletion(scope: CoroutineScope = this) =
      DeleteConfirmation(uiState, scope, TAG, "the item") { state, delete ->
        state.copy(delete = delete)
      }

  /** Confirms with [uid], recording what happens in [events]. */
  private fun DeleteConfirmation<ItemState>.confirmAs(
      uid: String? = "alice",
      delete: suspend (String) -> Unit = { deleted += it },
  ) =
      confirm(
          signedInUid = { uid },
          stopObserving = { events += "stop observing" },
          delete = {
            events += "delete"
            delete(it)
          },
          onFailure = { events += "resume observing for $it" },
      )

  // ----- Asking and cancelling -----

  @Test
  fun request_asksForConfirmation_andDeletesNothing() = runTest {
    deletion().request()
    advanceUntilIdle()

    assertTrue(uiState.value.delete.isConfirming)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertEquals(emptyList<String>(), deleted)
  }

  @Test
  fun request_isIgnoredWhenNothingCanBeDeleted() = runTest {
    uiState.value = ItemState(isShown = false)

    deletion().request()

    assertFalse(uiState.value.delete.isConfirming)
  }

  @Test
  fun request_clearsAnEarlierError() = runTest {
    uiState.value = ItemState(delete = DeleteState(hasError = true))

    deletion().request()

    assertFalse(uiState.value.delete.hasError)
  }

  @Test
  fun cancel_closesTheConfirmation_andItsError() = runTest {
    uiState.value = ItemState(delete = DeleteState(isConfirming = true, hasError = true))

    deletion().cancel()

    assertFalse(uiState.value.delete.isConfirming)
    assertFalse(uiState.value.delete.hasError)
  }

  @Test
  fun cancel_isIgnoredWhileDeleting() = runTest {
    uiState.value = ItemState(delete = DeleteState(isConfirming = true, isDeleting = true))

    deletion().cancel()

    assertTrue(uiState.value.delete.isConfirming)
  }

  // ----- Confirming -----

  @Test
  fun confirm_withoutAsking_deletesNothing() = runTest {
    deletion().confirmAs()
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertFalse(uiState.value.delete.isDeleted)
  }

  @Test
  fun confirm_stopsObservingThenDeletes_andIsDone() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs()
    assertTrue(uiState.value.delete.isDeleting)
    advanceUntilIdle()

    // Stops first, or the item would show as missing before the screen closes
    assertEquals(listOf("stop observing", "delete"), events)
    assertEquals(listOf("alice"), deleted)
    assertTrue(uiState.value.delete.isDeleted)
    assertFalse(uiState.value.delete.isDeleting)
    assertFalse(uiState.value.delete.isConfirming)
    assertFalse(uiState.value.isDeleteDialogShown)
  }

  @Test
  fun confirm_signedOut_showsTheError_andDeletesNothing() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(uid = null)
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertTrue(uiState.value.delete.hasError)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertFalse(uiState.value.delete.isDeleted)
  }

  @Test
  fun confirm_failure_keepsTheDialogWithTheError_andResumesObserving() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(delete = { throw IllegalStateException("refused") })
    advanceUntilIdle()

    assertEquals(listOf("stop observing", "delete", "resume observing for alice"), events)
    assertTrue(uiState.value.delete.hasError)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertFalse(uiState.value.delete.isDeleting)
    assertFalse(uiState.value.delete.isDeleted)
  }

  @Test
  fun whileDeleting_theDialogCantBeClosedOrConfirmedAgain() = runTest {
    val done = CompletableDeferred<Unit>()
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(delete = { done.await() })
    advanceUntilIdle()
    assertTrue(uiState.value.isDeleteDialogShown)
    deletion.cancel()
    deletion.confirmAs()
    advanceUntilIdle()
    assertEquals(1, events.count { it == "delete" })
    assertTrue(uiState.value.delete.isConfirming)

    done.complete(Unit)
    advanceUntilIdle()

    assertTrue(uiState.value.delete.isDeleted)
  }

  @Test
  fun leavingTheScreenWhileDeleting_isNotAFailure() = runTest {
    val screen = CoroutineScope(StandardTestDispatcher(testScheduler))
    val deletion = deletion(screen)
    deletion.request()
    deletion.confirmAs(delete = { CompletableDeferred<Unit>().await() })
    advanceUntilIdle()

    // The ViewModel is cleared, e.g. the user went back
    screen.cancel()
    advanceUntilIdle()

    assertFalse(uiState.value.delete.hasError)
    assertFalse(events.any { it.startsWith("resume observing") })
  }

  // ----- Shared rules of the state -----

  @Test
  fun deleteDialog_isShownOnlyWhileDeleteCanDoSomething() {
    val confirming = ItemState(delete = DeleteState(isConfirming = true))

    assertTrue(confirming.isDeleteDialogShown)
    assertTrue(
        confirming.copy(delete = confirming.delete.copy(isDeleting = true)).isDeleteDialogShown
    )
    assertFalse(ItemState().isDeleteDialogShown)
    assertFalse(confirming.copy(isShown = false).isDeleteDialogShown)
    assertFalse(
        confirming.copy(delete = confirming.delete.copy(isDeleted = true)).isDeleteDialogShown
    )
  }

  @Test
  fun afterRead_ofAMissingItem_closesTheConfirmationAndItsError() {
    val delete = DeleteState(isConfirming = true, hasError = true).afterRead(exists = false)

    assertEquals(DeleteState(), delete)
  }

  @Test
  fun afterRead_ofAnExistingItem_keepsTheConfirmationAndItsError() {
    val delete = DeleteState(isConfirming = true, hasError = true)

    assertEquals(delete, delete.afterRead(exists = true))
  }

  @Test
  fun afterRead_keepsWhetherItIsBeingDeletedOrDeleted() {
    val deleting = DeleteState(isConfirming = true, isDeleting = true)
    val deleted = DeleteState(isDeleted = true)

    assertEquals(DeleteState(isDeleting = true), deleting.afterRead(exists = false))
    assertEquals(deleted, deleted.afterRead(exists = false))
  }

  @Test
  fun aDeletionStep_leavesTheRestOfTheScreenAsItWas() = runTest {
    uiState.value = ItemState(isShown = true)

    deletion().request()

    assertEquals(
        ItemState(isShown = true, delete = DeleteState(isConfirming = true)),
        uiState.value,
    )
  }

  private companion object {
    const val TAG = "DeleteConfirmationTest"
  }
}
