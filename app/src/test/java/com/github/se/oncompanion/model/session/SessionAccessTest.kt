// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the concurrency fence independently of Firebase or connectivity. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionAccessTest {
  @After
  fun restoreGate() {
    SessionAccess.reopen()
  }

  @Test
  fun freezeDrainsActiveWorkAndRejectsNewWritesUntilUserCancels() = runTest {
    var drained = false
    val operation = backgroundScope.launch {
      SessionAccess.operation {
        try {
          awaitCancellation()
        } finally {
          drained = true
        }
      }
    }
    runCurrent()
    SessionAccess.freeze()
    assertTrue(drained)
    assertTrue(operation.isCompleted)
    try {
      SessionAccess.operation { fail("Write started while frozen") }
      fail("Expected rejection")
    } catch (_: IllegalStateException) {}
    SessionAccess.resume()
    assertEquals("preserved", SessionAccess.operation { "preserved" })
  }

  @Test
  fun sealRemovesListenersAndOldFlowsCannotBeCollectedByNextSession() = runTest {
    var removed = false
    val oldFlow =
        SessionAccess.snapshots<String> {
          trySend("private")
          awaitClose { removed = true }
        }
    val observer = backgroundScope.launch { oldFlow.collect() }
    runCurrent()
    SessionAccess.stopListeners()
    assertTrue(removed)
    assertTrue(observer.isCompleted)
    SessionAccess.reopen()
    try {
      oldFlow.collect { fail("Old session data exposed") }
      fail("Expected stale-flow rejection")
    } catch (_: IllegalStateException) {}
    var fresh = false
    val next = backgroundScope.launch {
      SessionAccess.snapshots<String> {
            trySend("next")
            awaitClose {}
          }
          .collect { fresh = it == "next" }
    }
    runCurrent()
    assertTrue(fresh)
    next.cancel()
  }

  @Test
  fun blockDeniesReadsAndResumeCannotBypassIncompleteCleanup() = runTest {
    SessionAccess.block()
    try {
      SessionAccess.firestore()
      fail("Database exposed")
    } catch (_: IllegalStateException) {}
    try {
      SessionAccess.resume()
      fail("Cleanup bypassed")
    } catch (_: IllegalStateException) {}
    try {
      SessionAccess.operation { fail("Read exposed") }
      fail("Expected rejection")
    } catch (_: IllegalStateException) {}
  }

  /** Coroutine cancellation cannot hide a still-running transaction from the sync barrier. */
  @Test
  fun cancelledTransactionAwaitRemainsTrackedUntilSdkAcknowledges() = runTest {
    val acknowledgement = TaskCompletionSource<String>()
    SessionAccess.transaction { acknowledgement.task }
    SessionAccess.freeze()
    val barrier = backgroundScope.async { SessionAccess.awaitTransactions() }
    runCurrent()
    assertFalse(barrier.isCompleted)
    acknowledgement.setResult("acknowledged")
    runCurrent()
    assertTrue(barrier.isCompleted)
    try {
      SessionAccess.transaction {
        fail("New transaction started")
        acknowledgement.task
      }
      fail("Expected rejection")
    } catch (_: IllegalStateException) {}
  }

  /** A late Auth SDK completion must settle before cleanup can sign out authentication. */
  @Test
  fun sealingWaitsForSdkAuthenticationThatSurvivedCoroutineCancellation() = runTest {
    val signIn = TaskCompletionSource<String>()
    SessionAccess.authentication { signIn.task }
    SessionAccess.freeze()
    val sealing = backgroundScope.async { SessionAccess.stopListeners() }
    runCurrent()
    assertFalse(sealing.isCompleted)
    signIn.setResult("signed-in")
    runCurrent()
    assertTrue(sealing.isCompleted)
    assertTrue(sealing.await() == Unit)
  }
}
