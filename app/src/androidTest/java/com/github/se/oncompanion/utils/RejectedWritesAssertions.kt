// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.utils

import com.github.se.oncompanion.model.session.RejectedWrites
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/** A repository write that the rules deny, with a name for the failure messages. */
typealias DeniedWrite = Pair<String, suspend () -> Unit>

/**
 * Runs each of [writes] in turn on [db], which the rules must deny, and checks that it returns at
 * once (saved on the device, without waiting for the server) and that the server's rejection is
 * then reported to [rejectedWrites] exactly once, so the user is told their change was undone.
 */
suspend fun assertEachRejectionIsReported(
    db: FirebaseFirestore,
    rejectedWrites: RejectedWrites,
    writes: List<DeniedWrite>,
) {
  for ((index, write) in writes.withIndex()) {
    val (name, block) = write
    // Also catches an earlier write reported twice
    assertEquals(
        "before $name, each earlier write is reported once",
        index,
        rejectedWrites.unseen.value,
    )
    val start = System.currentTimeMillis()
    withTimeout(5.seconds) { block() }
    val elapsed = System.currentTimeMillis() - start
    assertTrue("$name took $elapsed ms: it waited for the server", elapsed < 1_000)
    val reported = withTimeoutOrNull(10.seconds) { rejectedWrites.unseen.first { it > index } }
    assertNotNull("the server's rejection of $name wasn't reported", reported)
  }
  // Once the server answered every write and the failure listeners ran, nothing more comes in
  withTimeout(10.seconds) { db.waitForPendingWrites().await() }
  delay(500.milliseconds)
  assertEquals("each write is reported exactly once", writes.size, rejectedWrites.unseen.value)
}
