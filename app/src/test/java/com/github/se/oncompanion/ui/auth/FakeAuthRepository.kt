// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Hand-written fake [AuthRepository]. [onSignIn] decides what signInWithGoogle does (return a user,
 * throw, or suspend); every token it receives is recorded in [signInTokens].
 */
class FakeAuthRepository(
    var onSignIn: suspend (idToken: String) -> AuthUser = { AuthUser(uid = "uid-$it") },
) : AuthRepository {

  /** Number of sign-out attempts, including failures. */
  var signOutCalls = 0
    private set

  /** An immediate sign-out error; the session stays signed in. */
  var signOutError: Exception? = null

  val signInTokens = mutableListOf<String>()
  private val user = MutableStateFlow<AuthUser?>(null)

  override val currentUser: AuthUser?
    get() = user.value

  /** Failure raised when a new authentication observation starts. */
  var observeError: Exception? = null

  override fun observeCurrentUser(): Flow<AuthUser?> = flow {
    observeError?.let { throw it }
    emitAll(user)
  }

  override suspend fun signInWithGoogle(idToken: String): AuthUser {
    signInTokens += idToken
    val signedIn = onSignIn(idToken)
    user.value = signedIn
    return signedIn
  }

  override fun signOut() {
    signOutCalls++
    signOutError?.let { throw it }
    user.value = null
  }
}
