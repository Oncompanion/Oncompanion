package com.github.se.oncompanion.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInExceptionTest {

  @Test
  fun keepsReasonAndCause() {
    val cause = IllegalStateException("boom")
    val e = GoogleSignInException(GoogleSignInException.Reason.FAILED, cause)
    assertEquals(GoogleSignInException.Reason.FAILED, e.reason)
    assertSame(cause, e.cause)
  }

  @Test
  fun causeDefaultsToNull() {
    val e = GoogleSignInException(GoogleSignInException.Reason.CANCELLED)
    assertEquals(GoogleSignInException.Reason.CANCELLED, e.reason)
    assertNull(e.cause)
  }

  @Test
  fun isAnException() {
    val e: Any = GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT)
    assertTrue(e is Exception)
  }

  @Test
  fun canBeThrownAndCaught() {
    try {
      throw GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT)
    } catch (e: Exception) {
      assertEquals(GoogleSignInException.Reason.NO_ACCOUNT, (e as GoogleSignInException).reason)
    }
  }

  @Test
  fun reasonEnumHasExactlyTheThreeSpecifiedValues() {
    assertEquals(
        listOf("CANCELLED", "NO_ACCOUNT", "FAILED"),
        GoogleSignInException.Reason.entries.map { it.name },
    )
  }
}
