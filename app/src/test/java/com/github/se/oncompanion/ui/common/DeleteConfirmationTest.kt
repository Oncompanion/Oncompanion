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
      override val delete: DeletePhase = DeletePhase.IDLE,
  ) : HasDeletePhase {
    override val canDelete: Boolean
      get() = isShown && delete != DeletePhase.DELETING && delete != DeletePhase.DELETED
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

    assertEquals(DeletePhase.CONFIRMING, uiState.value.delete)
    assertTrue(uiState.value.isDeleteDialogShown)
    assertEquals(emptyList<String>(), deleted)
  }

  @Test
  fun request_isIgnoredWhenNothingCanBeDeleted() = runTest {
    uiState.value = ItemState(isShown = false)

    deletion().request()

    assertEquals(DeletePhase.IDLE, uiState.value.delete)
  }

  @Test
  fun request_clearsAnEarlierError() = runTest {
    uiState.value = ItemState(delete = DeletePhase.FAILED)

    deletion().request()

    assertEquals(DeletePhase.CONFIRMING, uiState.value.delete)
  }

  @Test
  fun cancel_closesTheConfirmation_andItsError() = runTest {
    uiState.value = ItemState(delete = DeletePhase.FAILED)

    deletion().cancel()

    assertEquals(DeletePhase.IDLE, uiState.value.delete)
  }

  @Test
  fun cancel_isIgnoredWhileDeleting() = runTest {
    uiState.value = ItemState(delete = DeletePhase.DELETING)

    deletion().cancel()

    assertEquals(DeletePhase.DELETING, uiState.value.delete)
  }

  // ----- Confirming -----

  @Test
  fun confirm_withoutAsking_deletesNothing() = runTest {
    deletion().confirmAs()
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertEquals(DeletePhase.IDLE, uiState.value.delete)
  }

  @Test
  fun confirm_stopsObservingThenDeletes_andIsDone() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs()
    assertEquals(DeletePhase.DELETING, uiState.value.delete)
    advanceUntilIdle()

    // Stops first, or the item would show as missing before the screen closes
    assertEquals(listOf("stop observing", "delete"), events)
    assertEquals(listOf("alice"), deleted)
    assertEquals(DeletePhase.DELETED, uiState.value.delete)
    assertFalse(uiState.value.isDeleteDialogShown)
  }

  @Test
  fun confirm_signedOut_showsTheError_andDeletesNothing() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(uid = null)
    advanceUntilIdle()

    assertEquals(emptyList<String>(), events)
    assertEquals(DeletePhase.FAILED, uiState.value.delete)
    assertTrue(uiState.value.isDeleteDialogShown)
  }

  @Test
  fun confirm_failure_keepsTheDialogWithTheError_andResumesObserving() = runTest {
    val deletion = deletion()
    deletion.request()

    deletion.confirmAs(delete = { throw IllegalStateException("refused") })
    advanceUntilIdle()

    assertEquals(listOf("stop observing", "delete", "resume observing for alice"), events)
    assertEquals(DeletePhase.FAILED, uiState.value.delete)
    assertTrue(uiState.value.isDeleteDialogShown)
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
    assertEquals(DeletePhase.DELETING, uiState.value.delete)

    done.complete(Unit)
    advanceUntilIdle()

    assertEquals(DeletePhase.DELETED, uiState.value.delete)
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

    assertEquals(DeletePhase.DELETING, uiState.value.delete)
    assertFalse(events.any { it.startsWith("resume observing") })
  }

  // ----- Shared rules of the state -----

  @Test
  fun deleteDialog_isShownOnlyWhileDeleteCanDoSomething() {
    // Whether the dialog shows, for each phase, with the item shown or not
    val cases =
        listOf(
            ItemState(delete = DeletePhase.IDLE) to false,
            ItemState(delete = DeletePhase.CONFIRMING) to true,
            ItemState(delete = DeletePhase.FAILED) to true,
            ItemState(delete = DeletePhase.DELETING) to true,
            ItemState(delete = DeletePhase.DELETED) to false,
            ItemState(isShown = false, delete = DeletePhase.CONFIRMING) to false,
            ItemState(isShown = false, delete = DeletePhase.FAILED) to false,
        )
    for ((state, shown) in cases) assertEquals("$state", shown, state.isDeleteDialogShown)
  }

  @Test
  fun afterRead_dropsTheConfirmationOfAMissingItem_andKeepsEverythingElse() {
    // An item deleted elsewhere can't be deleted here; a deletion under way or done goes on
    val cases =
        listOf(
            Triple(DeletePhase.IDLE, false, DeletePhase.IDLE),
            Triple(DeletePhase.CONFIRMING, false, DeletePhase.IDLE),
            Triple(DeletePhase.FAILED, false, DeletePhase.IDLE),
            Triple(DeletePhase.DELETING, false, DeletePhase.DELETING),
            Triple(DeletePhase.DELETED, false, DeletePhase.DELETED),
        ) + DeletePhase.entries.map { Triple(it, true, it) }
    for ((phase, exists, expected) in cases) {
      assertEquals("$phase, exists=$exists", expected, phase.afterRead(exists))
    }
  }

  @Test
  fun eachPhase_isAskingOrUnderWay_neverBoth() {
    // IDLE is neither: nothing asked and nothing deleted
    val cases =
        listOf(
            Triple(DeletePhase.IDLE, false, false),
            Triple(DeletePhase.CONFIRMING, true, false),
            Triple(DeletePhase.FAILED, true, false),
            Triple(DeletePhase.DELETING, false, true),
            Triple(DeletePhase.DELETED, false, true),
        )
    for ((phase, asking, underWay) in cases) {
      assertEquals("$phase isAsking", asking, phase.isAsking)
      assertEquals("$phase isUnderWay", underWay, phase.isUnderWay)
    }
  }

  @Test
  fun aDeletionStep_leavesTheRestOfTheScreenAsItWas() = runTest {
    uiState.value = ItemState(isShown = true)

    deletion().request()

    assertEquals(ItemState(isShown = true, delete = DeletePhase.CONFIRMING), uiState.value)
  }

  private companion object {
    const val TAG = "DeleteConfirmationTest"
  }
}
