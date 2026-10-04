package com.github.se.oncompanion.model.auth

import kotlinx.coroutines.flow.Flow

/** The signed-in user, without Firebase types so ViewModels and tests don't depend on Firebase. */
data class AuthUser(
    val uid: String,
    val email: String? = null,
    /** Full name from the Google account. */
    val displayName: String? = null,
    /**
     * First name from the Google account, used to pre-fill onboarding. Only known after
     * [AuthRepository.signInWithGoogle], until the app is closed: `null` when the user was signed
     * in by an earlier launch.
     */
    val givenName: String? = null,
) {
  /** Best guess of the first name: [givenName], or else the first word of [displayName]. */
  fun firstNameGuess(): String? =
      givenName?.takeIf { it.isNotBlank() }
          ?: displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
}

/** Signing in and out. Use [AuthRepositoryFirebase] in the app. */
interface AuthRepository {

  /** The signed-in user, or `null`. Firebase remembers the session, so this also works offline. */
  val currentUser: AuthUser?

  /** Emits the signed-in user (or `null`) now and every time it changes. */
  fun observeCurrentUser(): Flow<AuthUser?>

  /**
   * Signs in to Firebase with a Google ID token (see `GoogleCredentialProvider`). The account is
   * created on the first sign-in.
   *
   * @return the signed-in user, including [AuthUser.givenName] when Google provides it
   * @throws Exception if Firebase rejects the token or there is no connection
   */
  suspend fun signInWithGoogle(idToken: String): AuthUser

  /** Signs out of Firebase. Does nothing if nobody is signed in. */
  fun signOut()
}
