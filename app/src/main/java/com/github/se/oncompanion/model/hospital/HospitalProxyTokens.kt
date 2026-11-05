// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/** Supplies credentials without exposing Firebase or patient identities to HTTP parsing. */
interface HospitalProxyTokens {
  /** Returns the current Firebase ID token, refreshing expired credentials through the SDK. */
  suspend fun idToken(): String?

  /** Returns an App Check token; the SDK manages its cache and expiry. */
  suspend fun appCheckToken(): String?
}

/** Lazy access to the existing default Firebase app; construction makes no SDK/network calls. */
class FirebaseHospitalProxyTokens : HospitalProxyTokens {
  private val auth by lazy { FirebaseAuth.getInstance() }

  override suspend fun idToken(): String? {
    val user = auth.currentUser ?: return null
    val token = user.getIdToken(false).await().token
    return token.takeIf { auth.currentUser === user }
  }

  override suspend fun appCheckToken(): String =
      HospitalProxyAppCheck.instance.getAppCheckToken(false).await().token
}
