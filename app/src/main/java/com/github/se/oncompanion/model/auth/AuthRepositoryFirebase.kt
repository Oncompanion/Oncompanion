package com.github.se.oncompanion.model.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** [AuthRepository] backed by Firebase Authentication. */
class AuthRepositoryFirebase(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) :
    AuthRepository {

  override val currentUser: AuthUser?
    get() = auth.currentUser?.toAuthUser()

  override fun observeCurrentUser(): Flow<AuthUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
    auth.addAuthStateListener(listener) // also delivers the current state right away
    awaitClose { auth.removeAuthStateListener(listener) }
  }

  override suspend fun signInWithGoogle(idToken: String): AuthUser {
    val credential = GoogleAuthProvider.getCredential(idToken, null)
    val result = auth.signInWithCredential(credential).await()
    val user = result.user ?: throw IllegalStateException("Firebase returned no user after sign-in")
    // Google's profile claims (given_name, family_name...) are only available in the sign-in result
    val givenName = result.additionalUserInfo?.profile?.get(GOOGLE_GIVEN_NAME) as? String
    return user.toAuthUser().copy(givenName = givenName)
  }

  override fun signOut() {
    auth.signOut()
  }

  private fun FirebaseUser.toAuthUser() =
      AuthUser(uid = uid, email = email, displayName = displayName)

  private companion object {
    const val GOOGLE_GIVEN_NAME = "given_name"
  }
}
