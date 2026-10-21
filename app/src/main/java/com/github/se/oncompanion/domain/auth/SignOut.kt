// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.domain.auth

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.session.LocalSessionRepository
import com.github.se.oncompanion.model.session.LocalSessionRepositoryProvider
import com.github.se.oncompanion.model.session.SessionAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Durable marker: interrupted destructive cleanup must block all authenticated UI on restart. */
interface CleanupJournal {
  /** Whether cleanup must finish before another session can open. */
  val pending: Boolean

  /** Durably records or clears the recovery requirement; throws if storage fails. */
  fun setPending(pending: Boolean)
}

/** Session-owned memory that must be cleared before the operation gate can reopen. */
interface SessionMemory {
  /** Cancels old consumers and removes all retained user data synchronously. */
  fun clear()
}

/** Sign-out phases shared by Profile and the app's privacy boundary. */
enum class SignOutState {
  IDLE,
  SYNCHRONIZING,
  NEEDS_DISCARD_CONFIRMATION,
  CLEANING,
  FAILED,
  COMPLETE,
}

/** Coordinates bounded synchronization and fail-closed cleanup outside screen lifetimes. */
class SignOut(
    private val auth: AuthRepository = AuthRepositoryProvider.repository,
    private val local: LocalSessionRepository = LocalSessionRepositoryProvider.repository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val timeoutMillis: Long = 10_000,
) {
  private val mutableState = MutableStateFlow(SignOutState.IDLE)
  /** Current phase; only COMPLETE represents successful cleanup. */
  val state = mutableState.asStateFlow()
  private val completed = MutableStateFlow(0L)
  /** Changes after each secure cleanup, even if observers miss the short cleaning phase. */
  val generation = completed.asStateFlow()
  private var journal: CleanupJournal? = null
  private var expectedUid: String? = null
  private val memory = mutableSetOf<SessionMemory>()

  /** Registers authenticated navigation memory for synchronous cleanup. */
  @Synchronized
  fun register(owner: SessionMemory) {
    memory.add(owner)
  }

  /** Releases a navigation owner when its composition is disposed. */
  @Synchronized
  fun unregister(owner: SessionMemory) {
    memory.remove(owner)
  }

  @Synchronized
  private fun clearMemory() {
    memory.toList().forEach { it.clear() }
  }

  /** A completed new sign-in ends the previous sign-out result without resetting its generation. */
  @Synchronized
  fun sessionStarted() {
    mutableState.compareAndSet(SignOutState.COMPLETE, SignOutState.IDLE)
  }

  /** Installs durable recovery before any authenticated screen or repository is opened. */
  @Synchronized
  fun initialize(journal: CleanupJournal) {
    if (this.journal != null) return
    this.journal = journal
    local.prepare()
    if (journal.pending) {
      SessionAccess.block()
      mutableState.value = SignOutState.FAILED
    }
  }

  /** Freezes repository work before waiting for persisted pending writes, with a deadline. */
  @Synchronized
  fun begin(clearCredentials: suspend () -> Unit) {
    if (state.value != SignOutState.IDLE && state.value != SignOutState.COMPLETE) return
    expectedUid = auth.currentUser?.uid
    mutableState.value = SignOutState.SYNCHRONIZING
    scope.launch {
      try {
        withTimeout(timeoutMillis) {
          local.freeze()
          local.synchronize()
          check(auth.currentUser?.uid == expectedUid) { "Authentication changed during sign-out" }
        }
      } catch (_: Exception) {
        // No destructive action has happened. A timeout is uncertainty, never proof of no writes.
        mutableState.value = SignOutState.NEEDS_DISCARD_CONFIRMATION
        return@launch
      }
      cleanup(clearCredentials)
    }
  }

  /** Safe/default choice: preserve both the authenticated session and all local mutations. */
  @Synchronized
  fun staySignedIn() {
    if (state.value != SignOutState.NEEDS_DISCARD_CONFIRMATION) return
    local.resume()
    expectedUid = null
    mutableState.value = SignOutState.IDLE
  }

  /** Explicitly accepts losing unacknowledged local mutations. */
  @Synchronized
  fun discard(clearCredentials: suspend () -> Unit) {
    if (state.value != SignOutState.NEEDS_DISCARD_CONFIRMATION) return
    mutableState.value = SignOutState.CLEANING
    scope.launch { cleanup(clearCredentials) }
  }

  /** Retries incomplete cleanup while the global boundary keeps all personal screens closed. */
  @Synchronized
  fun retry(clearCredentials: suspend () -> Unit) {
    if (state.value != SignOutState.FAILED) return
    mutableState.value = SignOutState.CLEANING
    scope.launch { cleanup(clearCredentials) }
  }

  private suspend fun cleanup(clearCredentials: suspend () -> Unit) {
    mutableState.value = SignOutState.CLEANING
    try {
      val recovery = checkNotNull(journal) { "Cleanup journal must be initialized" }
      withTimeout(timeoutMillis) { local.freeze() }
      withTimeout(timeoutMillis) { local.seal() }
      recovery.setPending(true)
      clearMemory()
      // Keep trying independent cleanup stages, but never unlock after any failure.
      var failure: Exception? = null
      try {
        auth.signOut()
      } catch (error: Exception) {
        failure = error
      }
      try {
        withTimeout(timeoutMillis) { clearCredentials() }
      } catch (error: Exception) {
        failure = error
      }
      try {
        withTimeout(timeoutMillis) { local.clear() }
      } catch (error: Exception) {
        failure = error
      }
      failure?.let { throw it }
      recovery.setPending(false)
      local.reopen()
      expectedUid = null
      completed.value++
      mutableState.value = SignOutState.COMPLETE
    } catch (_: Exception) {
      mutableState.value = SignOutState.FAILED
    }
  }
}

/** One coordinator survives destruction of every authenticated ViewModel during cleanup. */
object SignOutProvider {
  /** Tests replace the coordinator together with their authentication and local-store fakes. */
  @Volatile var signOut = SignOut()
}
