// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.auth

import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Behavioral coverage for synchronization, explicit loss consent and fail-closed recovery. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutTest {
  private val auth = FakeAuthRepository()
  private val local = FakeLocalSessionRepository()
  private val journal = FakeCleanupJournal()

  @After
  fun restoreGate() {
    SessionAccess.reopen()
  }

  @Test
  fun acknowledgedChangesAreClearedOnlyAfterSyncAndCredentials() = runTest {
    auth.signInWithGoogle("one")
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    var clears = 0
    signOut.begin {
      assertNull(auth.currentUser)
      assertTrue(journal.pending)
      assertTrue(local.sealed)
      assertFalse(local.calls.contains("clear"))
      clears++
    }
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    assertEquals(listOf("freeze", "synchronize", "freeze", "seal", "clear", "reopen"), local.calls)
    assertEquals(1, clears)
    assertFalse(journal.pending)
  }

  @Test
  fun pendingWritesMustActuallyBeAcknowledgedBeforeSignOut() = runTest {
    auth.signInWithGoogle("one")
    val acknowledgement = CompletableDeferred<Unit>()
    local.synchronizeAction = { acknowledgement.await() }
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    signOut.begin {}
    runCurrent()
    assertEquals(SignOutState.SYNCHRONIZING, signOut.state.value)
    assertFalse(local.accepting)
    assertNotNull(auth.currentUser)
    assertFalse(local.calls.contains("clear"))
    acknowledgement.complete(Unit)
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
  }

  @Test
  fun offlineTimeoutNeedsConsentAndStayPreservesSessionAndMutations() = runTest {
    auth.signInWithGoogle("one")
    local.synchronizeAction = { CompletableDeferred<Unit>().await() }
    val signOut = SignOut(auth, local, backgroundScope, 100).apply { initialize(journal) }
    signOut.begin { fail("Credentials must remain") }
    runCurrent()
    advanceTimeBy(100)
    runCurrent()
    assertEquals(SignOutState.NEEDS_DISCARD_CONFIRMATION, signOut.state.value)
    assertNotNull(auth.currentUser)
    assertTrue(local.offlineChanges)
    assertFalse(journal.pending)
    signOut.staySignedIn()
    assertEquals(SignOutState.IDLE, signOut.state.value)
    assertTrue(local.accepting)
    assertTrue(local.offlineChanges)
    assertEquals(0, auth.signOutCalls)
  }

  @Test
  fun synchronizationErrorAlsoNeedsExplicitDiscard() = runTest {
    auth.signInWithGoogle("one")
    local.synchronizeAction = { error("unavailable") }
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    signOut.discard { fail("No consent requested") }
    signOut.begin {}
    runCurrent()
    assertEquals(SignOutState.NEEDS_DISCARD_CONFIRMATION, signOut.state.value)
    assertTrue(local.offlineChanges)
    signOut.discard {}
    signOut.discard { fail("Duplicate") }
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    assertFalse(local.offlineChanges)
    assertNull(auth.currentUser)
    assertEquals(1, auth.signOutCalls)
  }

  @Test
  fun everyCleanupFailureLocksAccessAndCanBeRetried() = runTest {
    for (stage in listOf("auth", "credentials", "clear", "seal", "journal")) {
      auth.signInWithGoogle(stage)
      val local = FakeLocalSessionRepository()
      val journal = FakeCleanupJournal()
      val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
      val failure = IllegalStateException(stage)
      auth.signOutError = if (stage == "auth") failure else null
      local.clearError = if (stage == "clear") failure else null
      local.sealError = if (stage == "seal") failure else null
      journal.failure = if (stage == "journal") failure else null
      signOut.begin { if (stage == "credentials") throw failure }
      runCurrent()
      assertEquals(stage, SignOutState.FAILED, signOut.state.value)
      assertFalse(local.accepting)
      assertFalse(local.calls.contains("reopen"))
      auth.signOutError = null
      local.clearError = null
      local.sealError = null
      journal.failure = null
      signOut.retry {}
      runCurrent()
      assertEquals(stage, SignOutState.COMPLETE, signOut.state.value)
    }
  }

  @Test
  fun duplicateRequestsAndCancelDuringCleanupCannotReopenTheSession() = runTest {
    auth.signInWithGoogle("one")
    val credentials = CompletableDeferred<Unit>()
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    signOut.begin { credentials.await() }
    signOut.begin { fail("Duplicate") }
    runCurrent()
    signOut.staySignedIn()
    signOut.discard { fail("Duplicate") }
    signOut.retry { fail("Duplicate") }
    assertEquals(SignOutState.CLEANING, signOut.state.value)
    assertTrue(journal.pending)
    credentials.complete(Unit)
    runCurrent()
    assertEquals(1, auth.signOutCalls)
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
  }

  @Test
  fun credentialTimeoutFailsClosedAndStillAttemptsCacheClearing() = runTest {
    auth.signInWithGoogle("one")
    val signOut = SignOut(auth, local, backgroundScope, 100).apply { initialize(journal) }
    signOut.begin { CompletableDeferred<Unit>().await() }
    runCurrent()
    advanceTimeBy(100)
    runCurrent()
    assertEquals(SignOutState.FAILED, signOut.state.value)
    assertTrue(local.calls.contains("clear"))
    assertTrue(journal.pending)
    assertFalse(local.calls.contains("reopen"))
  }

  @Test
  fun changedAuthenticationDoesNotTreatOldAcknowledgementAsSuccess() = runTest {
    auth.signInWithGoogle("one")
    local.synchronizeAction = { auth.signInWithGoogle("two") }
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    signOut.begin { fail("Authentication changed") }
    runCurrent()
    assertEquals(SignOutState.NEEDS_DISCARD_CONFIRMATION, signOut.state.value)
    assertFalse(local.calls.contains("clear"))
  }

  @Test
  fun interruptedCleanupIsBlockedOnRestartAndInitializationIsIdempotent() = runTest {
    journal.setPending(true)
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    assertEquals(SignOutState.FAILED, signOut.state.value)
    signOut.retry {}
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    signOut.initialize(FakeCleanupJournal(true))
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    assertFalse(journal.pending)
  }

  /** A timed-out attempt must not poison a later acknowledgement after reconnection. */
  @Test
  fun offlineStayReconnectAndSynchronizeAllowsSignOutWithoutDiscard() = runTest {
    auth.signInWithGoogle("one")
    val acknowledgement = CompletableDeferred<Unit>()
    local.synchronizeAction = { acknowledgement.await() }
    val signOut = SignOut(auth, local, backgroundScope, 100).apply { initialize(journal) }
    signOut.begin { fail("Offline writes must be preserved") }
    runCurrent()
    advanceTimeBy(100)
    runCurrent()
    assertEquals(SignOutState.NEEDS_DISCARD_CONFIRMATION, signOut.state.value)
    signOut.staySignedIn()
    assertTrue(local.offlineChanges)
    assertNotNull(auth.currentUser)
    acknowledgement.complete(Unit)
    signOut.begin {}
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    assertEquals(2, local.calls.count { it == "synchronize" })
    assertEquals(1, auth.signOutCalls)
  }

  /** Retrying is not permission to discard when synchronization still fails. */
  @Test
  fun repeatedSynchronizationFailuresStillRequireExplicitConsent() = runTest {
    auth.signInWithGoogle("one")
    local.synchronizeAction = { error("Synchronization failed") }
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    repeat(2) {
      signOut.begin { fail("No cleanup without acknowledgement or consent") }
      runCurrent()
      assertEquals(SignOutState.NEEDS_DISCARD_CONFIRMATION, signOut.state.value)
      assertTrue(local.offlineChanges)
      assertFalse(local.calls.contains("clear"))
      signOut.staySignedIn()
    }
    assertNotNull(auth.currentUser)
    assertEquals(0, auth.signOutCalls)
  }

  /** Old ViewModel scopes must be cancelled before a new session can use the store. */
  @Test
  fun registeredMemoryIsClearedBeforeAuthenticationAndCredentials() = runTest {
    auth.signInWithGoogle("one")
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    var cleared = false
    val owner =
        object : SessionMemory {
          override fun clear() {
            assertNotNull(auth.currentUser)
            cleared = true
          }
        }
    signOut.register(owner)
    val released =
        object : SessionMemory {
          override fun clear() {
            fail("Released owner")
          }
        }
    signOut.register(released)
    signOut.unregister(released)
    signOut.begin { assertTrue(cleared) }
    runCurrent()
    assertTrue(cleared)
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
  }

  /** A later app launch may use the next user's normal start route, without reusing old memory. */
  @Test
  fun startingNextSessionResetsCompletionButCannotBypassFailedCleanup() = runTest {
    auth.signInWithGoogle("one")
    val signOut = SignOut(auth, local, backgroundScope).apply { initialize(journal) }
    signOut.begin { error("credentials") }
    runCurrent()
    signOut.sessionStarted()
    assertEquals(SignOutState.FAILED, signOut.state.value)
    signOut.retry {}
    runCurrent()
    assertEquals(1L, signOut.generation.value)
    signOut.sessionStarted()
    assertEquals(SignOutState.IDLE, signOut.state.value)
    assertEquals(1L, signOut.generation.value)
    auth.signInWithGoogle("two")
    signOut.begin {}
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
    assertEquals(2L, signOut.generation.value)
  }
}
