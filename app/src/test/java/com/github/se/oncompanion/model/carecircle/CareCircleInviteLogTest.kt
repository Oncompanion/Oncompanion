// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore.RejectedWrite
import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * An invite code works like a password, so it must never reach the log, not even through the text
 * of a Firestore error. The repository's real writes are checked in `CareCircleInviteLogsTest`.
 */
@RunWith(RobolectricTestRunner::class)
class CareCircleInviteLogTest {

  private val code = "ABCDEFGHJK"
  private val tag = "CareCircleInviteRepo"

  @Before
  fun clearLogs() {
    ShadowLog.clear()
  }

  /** Everything logged under the repository's tag, including any attached error's stack trace. */
  private fun loggedText(): String =
      ShadowLog.getLogsForTag(tag).joinToString("\n") { item ->
        "${item.msg}\n${item.throwable?.let(Log::getStackTraceString).orEmpty()}"
      }

  @Test
  fun everyRejectedWrite_saysWhatFailedAndWhy_withoutTheCode() {
    val error =
        FirebaseFirestoreException(
            "No document to update: projects/p/databases/(default)/documents/invites/$code",
            FirebaseFirestoreException.Code.NOT_FOUND,
            IllegalStateException("invites/$code"),
        )
    for (write in RejectedWrite.entries) {
      ShadowLog.clear()
      CareCircleInviteRepositoryFirestore.logRejected(write, error)

      val text = loggedText()
      assertTrue(text, text.contains(write.label))
      assertTrue(text, text.contains("NOT_FOUND"))
      assertFalse(text, text.contains(code))
    }
  }

  @Test
  fun rejectedWrite_withAnotherKindOfError_namesItsTypeAndAttachesNothing() {
    CareCircleInviteRepositoryFirestore.logRejected(
        RejectedWrite.UPDATE,
        IllegalStateException("invites/$code"),
    )

    val item = ShadowLog.getLogsForTag(tag).single()
    assertEquals(Log.ERROR, item.type)
    assertTrue(item.msg, item.msg.contains("IllegalStateException"))
    assertFalse(item.msg, item.msg.contains(code))
    assertNull(item.throwable)
  }

  @Test
  fun skippedInvite_isLogged_withoutAnyId() {
    CareCircleInviteRepositoryFirestore.logSkippedInvite()

    val item = ShadowLog.getLogsForTag(tag).single()
    assertTrue(item.msg, item.msg.contains("skipped"))
    assertNull(item.throwable)
  }
}
