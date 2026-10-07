package com.github.se.oncompanion.ui.auth

import android.content.Context

/**
 * Hand-written fake [GoogleCredentialProvider]. [onGetToken] decides what getGoogleIdToken does
 * (return a token, throw, or suspend); the contexts it is called with are recorded in
 * [tokenRequests].
 */
class FakeGoogleCredentialProvider(
    var onGetToken: suspend () -> String = { "fake-token" },
) : GoogleCredentialProvider {

  val tokenRequests = mutableListOf<Context>()

  override suspend fun getGoogleIdToken(context: Context): String {
    tokenRequests += context
    return onGetToken()
  }

  override suspend fun clearCredentialState(context: Context) = Unit
}
