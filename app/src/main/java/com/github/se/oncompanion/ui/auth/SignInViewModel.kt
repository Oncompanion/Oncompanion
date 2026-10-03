package com.github.se.oncompanion.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.auth.isNetworkError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why signing in failed, so the screen can show the right message. */
enum class SignInError {
  /** No internet connection. */
  NO_CONNECTION,
  /** No Google account on the device. */
  NO_GOOGLE_ACCOUNT,
  /** Anything else. */
  FAILED,
}

/**
 * @property isLoading a sign-in is in progress
 * @property error the last error to show, or `null`
 * @property signedInUser set once signed in; the screen then leaves
 */
data class SignInUiState(
    val isLoading: Boolean = false,
    val error: SignInError? = null,
    val signedInUser: AuthUser? = null,
)

/** Signs the user in with Google (US-1, US-2). */
class SignInViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(SignInUiState())
  val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

  /**
   * Signs in: gets a Google ID token with [getGoogleIdToken] (it shows Google's dialog, so it needs
   * an Activity: the screen provides it, this ViewModel never holds a Context), then signs in to
   * Firebase. Does nothing while a sign-in is already in progress. If the user closes Google's
   * dialog, no error is shown.
   */
  fun signIn(getGoogleIdToken: suspend () -> String) {
    if (_uiState.value.isLoading) return
    _uiState.update { it.copy(isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        val user = authRepository.signInWithGoogle(getGoogleIdToken())
        _uiState.update { it.copy(isLoading = false, signedInUser = user) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        _uiState.update { it.copy(isLoading = false, error = e.toSignInError()) }
      }
    }
  }

  /** The error has been shown. */
  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  private fun Exception.toSignInError(): SignInError? =
      when {
        this is GoogleSignInException ->
            when (reason) {
              GoogleSignInException.Reason.CANCELLED -> null
              GoogleSignInException.Reason.NO_ACCOUNT -> SignInError.NO_GOOGLE_ACCOUNT
              GoogleSignInException.Reason.FAILED -> SignInError.FAILED
            }
        isNetworkError() -> SignInError.NO_CONNECTION
        else -> SignInError.FAILED
      }
}
