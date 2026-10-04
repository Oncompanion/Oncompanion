package com.github.se.oncompanion.ui.auth

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hand-written fake [AuthRepository]. [onSignIn] decides what signInWithGoogle does (return a user,
 * throw, or suspend); every token it receives is recorded in [signInTokens].
 */
class FakeAuthRepository(
    var onSignIn: suspend (idToken: String) -> AuthUser = { AuthUser(uid = "uid-$it") },
) : AuthRepository {

  val signInTokens = mutableListOf<String>()
  private val user = MutableStateFlow<AuthUser?>(null)

  override val currentUser: AuthUser?
    get() = user.value

  override fun observeCurrentUser(): Flow<AuthUser?> = user

  override suspend fun signInWithGoogle(idToken: String): AuthUser {
    signInTokens += idToken
    val signedIn = onSignIn(idToken)
    user.value = signedIn
    return signedIn
  }

  override fun signOut() {
    user.value = null
  }
}
