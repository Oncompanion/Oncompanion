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
      override val isConfirmingDelete: Boolean = false,
      override val isDeleting: Boolean = false,
      override val hasDeleteError: Boolean = false,
      override val isDeleted: Boolean = false,
  ) : DeletableUiState<ItemState> {
    override val canDelete: Boolean
      get() = isShown && !isDeleting && !isDeleted

    override fun withDelete(
        isConfirmingDelete: Boolean,
        isDeleting: Boolean,
        hasDeleteError: Boolean,
        isDeleted: Boolean,
    ) =
        copy(
            isConfirmingDelete = isConfirmingDelete,
            isDeleting = isDeleting,
            hasDeleteError = hasDeleteError,
            isDeleted = isDeleted,
        )
  }

  private val uiState = MutableStateFlow(ItemState())
  private val deleted = mutableListOf<String>()
  private val events = mutableListOf<String>()

  private fun TestScope.deletion(scope: CoroutineScope = this) =
      DeleteConfirmation(uiState, scope, TAG, "the item")

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

    assertTrue(uiState.value.isConfirmingDelete)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertEquals(emptyList<String>(), deleted)
  }

  @Test
  fun request_isIgnoredWhenNothingCanBeDeleted() = runTest {
    uiState.value = ItemState(isShown = false)

    deletion().request()

    assertFalse(uiState.value.isConfirmingDelete)
  }

  @Test
  fun request_clearsAnEarlierError() = runTest {
    uiState.value = ItemState(hasDeleteError = true)

    deletion().request()

    assertFalse(uiState.value.hasDeleteError)
  }

  @Test
  fun cancel_closesTheConfirmation_andItsError() = runTest {
    uiState.value = ItemState(isConfirmingDelete = true, hasDeleteError = true)

    deletion().cancel()

    assertFalse(uiState.value.isConfirmingDelete)
    assertFalse(uiState.value.hasDeleteError)
  }

  @Test
  fun cancel_isIgnoredWhileDeleting() = runTest {
    uiState.value = ItemState(isConfirmingDelete = true, isDeleting = true)

    deletion().cancel()

    assertTrue(uiState.value.isConfirmingDelete)
  }

  // ----- Confirming -----

  @Test
  fun confirm_withoutAsking_deletesNothing() = runTest {
    deletion().confirmAs()
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertFalse(uiState.value.isDeleted)
  }

  @Test
  fun confirm_stopsObservingThenDeletes_andIsDone() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs()
    assertTrue(uiState.value.isDeleting)
    advanceUntilIdle()

    // Stops first, or the item would show as missing before the screen closes
    assertEquals(listOf("stop observing", "delete"), events)
    assertEquals(listOf("alice"), deleted)
    assertTrue(uiState.value.isDeleted)
    assertFalse(uiState.value.isDeleting)
    assertFalse(uiState.value.isConfirmingDelete)
    assertFalse(uiState.value.isDeleteDialogShown)
  }

  @Test
  fun confirm_signedOut_showsTheError_andDeletesNothing() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(uid = null)
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertTrue(uiState.value.hasDeleteError)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertFalse(uiState.value.isDeleted)
  }

  @Test
  fun confirm_failure_keepsTheDialogWithTheError_andResumesObserving() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(delete = { throw IllegalStateException("refused") })
    advanceUntilIdle()

    assertEquals(listOf("stop observing", "delete", "resume observing for alice"), events)
    assertTrue(uiState.value.hasDeleteError)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertFalse(uiState.value.isDeleting)
    assertFalse(uiState.value.isDeleted)
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
    assertTrue(uiState.value.isConfirmingDelete)

    done.complete(Unit)
    advanceUntilIdle()

    assertTrue(uiState.value.isDeleted)
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

    assertFalse(uiState.value.hasDeleteError)
    assertFalse(events.any { it.startsWith("resume observing") })
  }

  // ----- Shared rules of the state -----

  @Test
  fun deleteDialog_isShownOnlyWhileDeleteCanDoSomething() {
    val confirming = ItemState(isConfirmingDelete = true)

    assertTrue(confirming.isDeleteDialogShown)
    assertTrue(confirming.copy(isDeleting = true).isDeleteDialogShown)
    assertFalse(ItemState().isDeleteDialogShown)
    assertFalse(confirming.copy(isShown = false).isDeleteDialogShown)
    assertFalse(confirming.copy(isDeleted = true).isDeleteDialogShown)
  }

  @Test
  fun afterRead_ofAMissingItem_closesTheConfirmationAndItsError() {
    val state =
        ItemState(isConfirmingDelete = true, hasDeleteError = true).afterRead(exists = false)

    assertFalse(state.isConfirmingDelete)
    assertFalse(state.hasDeleteError)
  }

  @Test
  fun afterRead_ofAnExistingItem_keepsTheConfirmationAndItsError() {
    val state = ItemState(isConfirmingDelete = true, hasDeleteError = true).afterRead(exists = true)

    assertTrue(state.isConfirmingDelete)
    assertTrue(state.hasDeleteError)
  }

  private companion object {
    const val TAG = "DeleteConfirmationTest"
  }
}
