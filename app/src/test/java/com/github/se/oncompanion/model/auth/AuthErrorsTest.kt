package com.github.se.oncompanion.model.auth

import com.github.se.oncompanion.ui.auth.GoogleSignInException
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthErrorsTest {

  @Test
  fun firebaseNetworkException_isNetworkError() {
    assertTrue(FirebaseNetworkException("offline").isNetworkError())
  }

  @Test
  fun ioException_isNetworkError() {
    assertTrue(IOException("io").isNetworkError())
  }

  @Test
  fun ioExceptionSubclasses_areNetworkErrors() {
    assertTrue(UnknownHostException("host").isNetworkError())
    assertTrue(SocketTimeoutException("timeout").isNetworkError())
  }

  @Test
  fun otherExceptions_areNotNetworkErrors() {
    assertFalse(IllegalStateException("boom").isNetworkError())
    assertFalse(FirebaseException("not a network problem").isNetworkError())
    assertFalse(GoogleSignInException(GoogleSignInException.Reason.FAILED).isNetworkError())
  }
}
