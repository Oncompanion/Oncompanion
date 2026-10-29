// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/** Local session lifecycle; none of these operations delete remote documents. */
interface LocalSessionRepository {
  /** Captures legacy queue owners once, before any Firestore operations start in this process. */
  fun prepare()

  /** Prevents new work and drains repository operations already in progress. */
  suspend fun freeze()

  /** Waits for actual server acknowledgements, including writes restored from disk. */
  suspend fun synchronize()

  /** Keeps the current authenticated session and its offline changes. */
  fun resume()

  /** Blocks reads and stops listeners, including during recovery after process death. */
  suspend fun seal()

  /** Terminates the old SDK and clears its persistent documents and mutation queue. */
  suspend fun clear()

  /** Allows the next session to lazily obtain a fresh persistent SDK instance. */
  fun reopen()
}

/** Firebase lifecycle adapter. The terminated instance is retained only for cleanup retries. */
class LocalSessionRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = FirebaseFirestore::getInstance,
    private val inspectLegacyOwners: (FirebaseFirestore) -> Set<String> =
        FirestoreMutationQueue::owners,
) : LocalSessionRepository {
  private var oldDb: FirebaseFirestore? = null
  private var termination: Task<Void>? = null
  private var clearing: Task<Void>? = null

  private var prepared = false
  private var legacyOwners: Set<String>? = null

  override fun prepare() {
    if (prepared) return
    prepared = true
    // Failure remains uncertainty; never erase a cache whose legacy queues could not be read.
    legacyOwners = runCatching { inspectLegacyOwners(dbProvider()) }.getOrNull()
  }

  override suspend fun freeze() = SessionAccess.freeze()

  override suspend fun synchronize() {
    SessionAccess.awaitTransactions()
    val db = dbProvider()
    val auth = FirebaseAuth.getInstance(db.app)
    val uid = auth.currentUser?.uid.orEmpty()
    db.waitForPendingWrites().await()
    check(auth.currentUser?.uid.orEmpty() == uid) {
      "Authentication changed during synchronization"
    }
    val owners = checkNotNull(legacyOwners) { "Legacy mutation queues could not be inspected" }
    legacyOwners = owners - uid
    check(legacyOwners!!.isEmpty()) { "Another user still has unacknowledged legacy writes" }
  }

  override fun resume() = SessionAccess.resume()

  override suspend fun seal() = SessionAccess.stopListeners()

  override suspend fun clear() {
    val db = oldDb ?: dbProvider().also { oldDb = it }
    // Retain tasks across coroutine timeouts: termination itself cannot be called twice.
    val stop = termination ?: db.terminate().also { termination = it }
    stop.await()
    val clear =
        clearing?.takeUnless { it.isComplete && !it.isSuccessful }
            ?: db.clearPersistence().also { clearing = it }
    clear.await()
  }

  override fun reopen() {
    oldDb = null
    termination = null
    clearing = null
    legacyOwners = emptySet()
    SessionAccess.reopen()
  }
}

/** Replaceable local lifecycle for production and tests. */
object LocalSessionRepositoryProvider {
  /** Persistent Firestore is the normal offline store. */
  @Volatile var repository: LocalSessionRepository = LocalSessionRepositoryFirestore()
}
