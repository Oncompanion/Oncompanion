package com.github.se.oncompanion.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The profile details needed by the read-only Profile screen. */
data class ProfileDetails(
    val firstName: String,
    val familyName: String?,
    val cancerType: String?,
    val email: String?,
    val memberSince: Instant?,
)

/** What the Profile screen displays while resolving the signed-in user's profile. */
sealed interface ProfileUiState {
  data object Loading : ProfileUiState

  data object SignedOut : ProfileUiState

  data object MissingProfile : ProfileUiState

  data class Loaded(val details: ProfileDetails) : ProfileUiState

  data object Error : ProfileUiState

  /** Confirmation retains the current screen until sign-out starts; failures allow retry. */
  data class ConfirmingSignOut(
      val profile: ProfileUiState,
      val isSigningOut: Boolean = false,
      val failed: Boolean = false,
  ) : ProfileUiState

  /** Firebase is signed out and credential cleanup has finished; the host opens Login. */
  data object SignOutComplete : ProfileUiState
}

/** Observes the authenticated profile and handles confirmed sign-out (US-18, US-19). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val profileRepository: UserProfileRepository = UserProfileRepositoryFirestore(),
) : ViewModel() {

  private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
  val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    observeProfile()
  }

  /** Restarts observing after a profile or authentication error. */
  fun retry() {
    if (
        _uiState.value is ProfileUiState.ConfirmingSignOut ||
            _uiState.value == ProfileUiState.SignOutComplete
    )
        return
    observeProfile()
  }

  /** Opens confirmation without changing the authenticated session. */
  fun requestSignOut() {
    val state = _uiState.value
    if (
        state !is ProfileUiState.ConfirmingSignOut &&
            state != ProfileUiState.SignedOut &&
            state != ProfileUiState.SignOutComplete
    ) {
      _uiState.value = ProfileUiState.ConfirmingSignOut(state)
    }
  }

  /** Dismisses confirmation while keeping the current session and profile. */
  fun cancelSignOut() {
    val state = _uiState.value as? ProfileUiState.ConfirmingSignOut ?: return
    if (!state.isSigningOut) _uiState.value = state.profile
  }

  /**
   * Reuses Firebase sign-out, immediately drops profile state, then clears the account picker. The
   * screen supplies credential cleanup because it needs a Context, like Google sign-in. Firebase
   * errors keep the confirmation open for retry. Credential cleanup is best effort, matching
   * GoogleCredentialProvider: it must not leave signed-out users on authenticated screens.
   * Firestore persistence and pending offline writes are retained; the host must destroy both
   * active and saved authenticated navigation stacks so their ViewModels cannot be restored.
   */
  fun confirmSignOut(clearCredentialState: suspend () -> Unit) {
    val state = _uiState.value as? ProfileUiState.ConfirmingSignOut ?: return
    if (state.isSigningOut) return
    _uiState.value = state.copy(isSigningOut = true, failed = false)
    try {
      authRepository.signOut()
    } catch (error: Exception) {
      _uiState.value = state.copy(failed = true)
      return
    }
    // Stop cached profile emissions before clearing every reference to the previous user's details.
    observation?.cancel()
    _uiState.value = ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, isSigningOut = true)
    viewModelScope.launch {
      try {
        clearCredentialState()
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        Log.w(TAG, "Couldn't clear the Google credential state after sign-out", error)
      }
      _uiState.value = ProfileUiState.SignOutComplete
    }
  }

  private fun observeProfile() {
    observation?.cancel()
    _uiState.value = ProfileUiState.Loading
    observation = viewModelScope.launch {
      authRepository
          .observeCurrentUser()
          .flatMapLatest { user ->
            if (user == null) {
              flowOf(ProfileUiState.SignedOut)
            } else {
              profileRepository.observeProfile(user.uid).map { profile ->
                if (profile == null) {
                  ProfileUiState.MissingProfile
                } else {
                  ProfileUiState.Loaded(
                      ProfileDetails(
                          firstName = profile.firstName,
                          familyName = profile.familyName,
                          cancerType = profile.cancerType,
                          email = user.email,
                          memberSince = profile.createdAt,
                      )
                  )
                }
              }
            }
          }
          .catch { error ->
            if (error is CancellationException) throw error
            Log.e(TAG, "Couldn't observe the signed-in user's profile", error)
            emit(ProfileUiState.Error)
          }
          .collect { profile ->
            val state = _uiState.value
            _uiState.value =
                if (
                    state is ProfileUiState.ConfirmingSignOut && profile != ProfileUiState.SignedOut
                )
                    state.copy(profile = profile)
                else profile
          }
    }
  }

  private companion object {
    const val TAG = "ProfileViewModel"
  }
}
