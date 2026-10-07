package com.github.se.oncompanion.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
  fun reasonEnumHasExactlyTheFourSpecifiedValues() {
    assertEquals(
        listOf("CANCELLED", "NO_ACCOUNT", "NETWORK", "FAILED"),
        GoogleSignInException.Reason.entries.map { it.name },
    )
  }
}
