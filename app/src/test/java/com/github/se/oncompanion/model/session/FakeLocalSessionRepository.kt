// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import com.github.se.oncompanion.domain.auth.CleanupJournal

/** Local lifecycle fake with controllable acknowledgement and cleanup failures. */
class FakeLocalSessionRepository : LocalSessionRepository {
  val calls = mutableListOf<String>()
  var accepting = true
  var sealed = false
  var offlineChanges = true
  var synchronizeAction: suspend () -> Unit = {}
  var clearError: Exception? = null
  var sealError: Exception? = null

  override fun prepare() {}

  override suspend fun freeze() {
    calls += "freeze"
    accepting = false
  }

  override suspend fun synchronize() {
    calls += "synchronize"
    synchronizeAction()
    offlineChanges = false
  }

  override fun resume() {
    calls += "resume"
    accepting = true
  }

  override suspend fun seal() {
    calls += "seal"
    sealed = true
    sealError?.let { throw it }
  }

  override suspend fun clear() {
    calls += "clear"
    clearError?.let { throw it }
    offlineChanges = false
  }

  override fun reopen() {
    calls += "reopen"
    sealed = false
    accepting = true
  }
}

/** In-memory journal fake, including durable-write failure injection. */
class FakeCleanupJournal(pending: Boolean = false) : CleanupJournal {
  private var flag = pending
  override val pending: Boolean
    get() = flag

  var failure: Exception? = null

  override fun setPending(pending: Boolean) {
    failure?.let { throw it }
    flag = pending
  }
}
