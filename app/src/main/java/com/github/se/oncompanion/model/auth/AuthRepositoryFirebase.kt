package com.github.se.oncompanion.model.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/**
 * [AuthRepository] backed by Firebase Authentication.
 *
 * Firebase is only accessed when first used, not when the repository is created: screens can create
 * it (e.g. as a ViewModel default) even where Firebase isn't initialized, like unit tests.
 */
class AuthRepositoryFirebase(authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() }) :
    AuthRepository {

  /** Uses the given [FirebaseAuth] instance, e.g. one connected to the emulator. */
  constructor(auth: FirebaseAuth) : this({ auth })

  private val auth: FirebaseAuth by lazy(authProvider)

  override val currentUser: AuthUser?
    get() = auth.currentUser?.toAuthUser()

  override fun observeCurrentUser(): Flow<AuthUser?> {
    val states = callbackFlow {
      val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.toAuthUser()) }
      auth.addAuthStateListener(listener) // also delivers the current state right away
      awaitClose { auth.removeAuthStateListener(listener) }
    }
    // Firebase notifies listeners on every signOut(), even when already signed out, and delivers
    // these callbacks later on the main thread, so the same state can arrive twice in a row.
    return states.distinctUntilChanged()
  }

  override suspend fun signInWithGoogle(idToken: String): AuthUser {
    val credential = GoogleAuthProvider.getCredential(idToken, null)
    val result = auth.signInWithCredential(credential).await()
    val user = result.user ?: throw IllegalStateException("Firebase returned no user after sign-in")
    // Google's profile claims (given_name, family_name...) are only available in the sign-in result
    val givenName = result.additionalUserInfo?.profile?.get(GOOGLE_GIVEN_NAME) as? String
    lastGivenName = givenName?.let { user.uid to it }
    return user.toAuthUser()
  }

  override fun signOut() {
    lastGivenName = null
    auth.signOut()
  }

  private fun FirebaseUser.toAuthUser() =
      AuthUser(
          uid = uid,
          email = email,
          displayName = displayName,
          givenName = lastGivenName?.takeIf { it.first == uid }?.second,
      )

  private companion object {
    const val GOOGLE_GIVEN_NAME = "given_name"

    /**
     * Given name of the last user who signed in, with their uid. Firebase doesn't keep it, so it is
     * kept here for as long as the app runs, shared by every instance (screens create their own).
     */
    @Volatile var lastGivenName: Pair<String, String>? = null
  }
}
