// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import com.google.firebase.firestore.FirebaseFirestore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull

/**
 * Checks that [operation] returns without the server. Call it only with Firestore's network
 * disabled: offline, an operation that waited for the server would never return, so this proves it
 * without timing it (a time limit fails on a slow emulator, and online the server answers within it
 * anyway).
 */
suspend fun assertReturnsWithoutServer(name: String, operation: suspend () -> Unit) {
  val returned =
      withTimeoutOrNull(5.seconds) {
        operation()
        true
      }
  assertNotNull("$name didn't return offline: it waited for the server", returned)
}

/**
 * Runs [operation] with [db]'s network disabled, checks it returns without the server
 * ([assertReturnsWithoutServer]), then reconnects so the server receives it, e.g. to reject it. It
 * always leaves the network enabled, so don't call it from a test that is already offline.
 */
suspend fun assertReturnsOffline(
    db: FirebaseFirestore,
    name: String,
    operation: suspend () -> Unit,
) {
  db.disableNetwork().await()
  try {
    assertReturnsWithoutServer(name, operation)
  } finally {
    db.enableNetwork().await()
  }
}
