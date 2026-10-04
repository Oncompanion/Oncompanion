package com.github.se.oncompanion.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.auth.isNetworkError
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
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

/** Where to go after signing in. */
enum class AfterSignIn {
  /** First sign-in (no profile yet): onboarding creates the profile. */
  ONBOARDING,
  /** Returning user (profile exists): straight to the Overview. */
  OVERVIEW,
}

/**
 * @property isLoading a sign-in is in progress
 * @property error the last error to show, or `null`
 * @property signedInUser set once signed in
 * @property next where to go, set once signed in; the screen then leaves
 */
data class SignInUiState(
    val isLoading: Boolean = false,
    val error: SignInError? = null,
    val signedInUser: AuthUser? = null,
    val next: AfterSignIn? = null,
)

/** Signs the user in with Google (US-1, US-2). */
class SignInViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val profileRepository: UserProfileRepository = UserProfileRepositoryFirestore(),
) : ViewModel() {

  private val _uiState = MutableStateFlow(SignInUiState())
  val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

  /**
   * Signs in: gets a Google ID token with [getGoogleIdToken], then signs in to Firebase. Showing
   * Google's dialog needs an Activity, so the screen provides that step: this ViewModel doesn't
   * store a Context, but [getGoogleIdToken] usually captures the Activity while the dialog is open
   * (e.g. across a rotation, until the dialog returns). Does nothing while a sign-in is already in
   * progress. If the user closes Google's dialog, no error is shown.
   *
   * Once signed in, checks whether the user already has a profile to decide where to go next
   * ([SignInUiState.next]). If that check fails, the error is shown like a sign-in error, rather
   * than guessing and sending a returning user to onboarding.
   */
  fun signIn(getGoogleIdToken: suspend () -> String) {
    if (_uiState.value.isLoading) return
    _uiState.update { it.copy(isLoading = true, error = null) }
    viewModelScope.launch {
      try {
        val user = authRepository.signInWithGoogle(getGoogleIdToken())
        val hasProfile = profileRepository.getProfile(user.uid) != null
        val next = if (hasProfile) AfterSignIn.OVERVIEW else AfterSignIn.ONBOARDING
        _uiState.update { it.copy(isLoading = false, signedInUser = user, next = next) }
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
              GoogleSignInException.Reason.NETWORK -> SignInError.NO_CONNECTION
              GoogleSignInException.Reason.FAILED -> SignInError.FAILED
            }
        isNetworkError() -> SignInError.NO_CONNECTION
        else -> SignInError.FAILED
      }
}
