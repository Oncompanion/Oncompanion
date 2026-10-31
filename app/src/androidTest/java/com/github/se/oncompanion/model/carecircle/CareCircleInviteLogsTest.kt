// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Makes the Firestore emulator really reject the repository's writes and reads the device's log, so
 * every write path is covered. Only the repository's own lines are checked: the Firestore SDK logs
 * the path of a rejected write itself, which this app can't change.
 */
@RunWith(AndroidJUnit4::class)
class CareCircleInviteLogsTest {

  private val db
    get() = FirebaseEmulator.firestore

  private val repository by lazy { CareCircleInviteRepositoryFirestore(db) }
  private lateinit var aliceUid: String
  private lateinit var logsSince: String

  // No such invite exists, so the rules reject any change to it
  private val unknownCode = "ABCDEFGHJK"
  private val julie =
      CareCircleMemberDetails(
          firstName = "Julie",
          relationship = Relationship.DAUGHTER,
          permissions = setOf(CarePermission.SYMPTOMS),
      )

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    logsSince = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /** The repository's log lines since the test started. */
  private fun repositoryLogs(): String {
    val command =
        arrayOf("logcat", "-d", "-v", "raw", "-T", logsSince, "-s", "CareCircleInviteRepo:V")
    return ProcessBuilder(*command).start().inputStream.bufferedReader().use { it.readText() }
  }

  /** Waits for the repository to log a line containing [expected]: rejections arrive later. */
  private suspend fun awaitLog(expected: String): String =
      withTimeout(10_000) {
        var logs = repositoryLogs()
        while (!logs.contains(expected)) {
          delay(100)
          logs = repositoryLogs()
        }
        logs
      }

  @Test
  fun rejectedUpdate_logsNoCode(): Unit = runBlocking {
    repository.updateInvite(unknownCode, julie, null)

    val logs = awaitLog("rejected the update")
    assertTrue(logs, logs.contains("PERMISSION_DENIED"))
    assertFalse(logs, logs.contains(unknownCode))
  }

  @Test
  fun rejectedCancellation_logsNoCode(): Unit = runBlocking {
    repository.cancelInvite(unknownCode)

    val logs = awaitLog("rejected the cancellation")
    assertFalse(logs, logs.contains(unknownCode))
  }

  @Test
  fun rejectedRenewal_logsNeitherCode(): Unit = runBlocking {
    val expired =
        CareCircleInvite(
            inviteCode = unknownCode,
            ownerUid = aliceUid,
            ownerFirstName = "Alice",
            details = julie,
        )

    val renewed = repository.renewInvite(expired)

    val logs = awaitLog("rejected the renewal")
    assertFalse(logs, logs.contains(unknownCode))
    assertFalse(logs, logs.contains(renewed.inviteCode))
  }

  @Test
  fun skippedInvite_logsNoCode(): Unit = runBlocking {
    // Offline, so the damaged invite stays on the device: the rules would reject it on the server
    db.disableNetwork().await()
    db.collection(COLLECTION)
        .document(unknownCode)
        .set(mapOf("ownerUid" to aliceUid, "redeemerUid" to null))

    val invites = withTimeout(10_000) { repository.observePendingInvites(aliceUid).first() }

    assertTrue(invites.isEmpty())
    val logs = awaitLog("skipped")
    assertFalse(logs, logs.contains(unknownCode))
  }
}
