// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.session

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.tasks.await

/** Fences repository work across sign-out without disabling Firestore's persistent cache. */
object SessionAccess {
  private val lock = Any()
  private var accepting = true
  private var sealed = false
  private var generation = 0L
  private val operations = mutableSetOf<Job>()
  private val observations = mutableSetOf<Job>()
  private val transactions = mutableSetOf<Task<*>>()
  private val authentications = mutableSetOf<Task<*>>()

  /** Resolves the current SDK instance lazily, never retaining a terminated instance. */
  fun firestore(): FirebaseFirestore =
      synchronized(lock) {
        check(!sealed) { "Local session cleanup is incomplete" }
        FirebaseFirestore.getInstance()
      }

  /** Runs one repository call in a child job that sign-out can stop and drain. */
  suspend fun <T> operation(block: suspend () -> T): T = coroutineScope {
    currentCoroutineContext().ensureActive()
    val job = currentCoroutineContext()[Job]!!
    synchronized(lock) {
      check(accepting && !sealed) { "Sign-out is in progress" }
      operations.add(job)
    }
    try {
      block()
    } finally {
      synchronized(lock) { operations.remove(job) }
    }
  }

  /** Tracks server transactions, whose SDK tasks survive cancellation of a coroutine await. */
  fun <T> transaction(start: () -> Task<T>): Task<T> = track(transactions, start)

  /** Tracks SDK sign-in tasks so late results cannot reauthenticate after cleanup. */
  fun <T> authentication(start: () -> Task<T>): Task<T> = track(authentications, start)

  private fun <T> track(tasks: MutableSet<Task<*>>, start: () -> Task<T>): Task<T> =
      synchronized(lock) {
        check(accepting && !sealed) { "Sign-out is in progress" }
        start().also { task ->
          tasks.add(task)
          task.addOnCompleteListener({ command -> command.run() }) {
            synchronized(lock) { tasks.remove(task) }
          }
        }
      }

  /** Stops new operations and drains synchronous enqueues before the acknowledgement barrier. */
  suspend fun freeze() {
    val jobs =
        synchronized(lock) {
          accepting = false
          operations.toList()
        }
    jobs.forEach { it.cancel() }
    jobs.joinAll()
  }

  /** Waits for in-flight transactions before checking Firestore's persisted mutation queue. */
  suspend fun awaitTransactions() {
    val tasks = synchronized(lock) { transactions.toList() }
    tasks.forEach { it.await() }
  }

  /** Restores access after the user keeps their offline changes. */
  fun resume() =
      synchronized(lock) {
        check(!sealed)
        accepting = true
      }

  /** Blocks access immediately, including on startup after interrupted cleanup. */
  fun block() =
      synchronized(lock) {
        accepting = false
        sealed = true
        generation++
      }

  /** Removes every repository listener before the SDK is terminated. */
  suspend fun stopListeners() {
    block()
    // Firebase Auth tasks cannot be cancelled by cancelling their coroutine await.
    val signIns = synchronized(lock) { authentications.toList() }
    signIns.forEach { it.await() }
    val jobs = synchronized(lock) { observations.toList() }
    jobs.forEach { it.cancel() }
    jobs.joinAll()
  }

  /** Opens a fresh session only after persistent cleanup has succeeded. */
  fun reopen() =
      synchronized(lock) {
        transactions.clear()
        sealed = false
        accepting = true
      }

  /** Owns callback listeners and refuses to recollect a flow from an earlier session. */
  fun <T> snapshots(block: suspend ProducerScope<T>.() -> Unit): Flow<T> {
    val version = synchronized(lock) { generation }
    return flow {
      coroutineScope {
        val job = currentCoroutineContext()[Job]!!
        synchronized(lock) {
          check(!sealed && version == generation) { "The session has ended" }
          observations.add(job)
        }
        try {
          emitAll(callbackFlow(block))
        } finally {
          synchronized(lock) { observations.remove(job) }
        }
      }
    }
  }
}
