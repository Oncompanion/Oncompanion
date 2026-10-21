// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutProvider
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
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
    val hospital: String?,
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
      val pendingChanges: Boolean = false,
  ) : ProfileUiState

  /** Firebase is signed out and credential cleanup has finished; the host opens Login. */
  data object SignOutComplete : ProfileUiState
}

/** Observes the authenticated profile and handles confirmed sign-out (US-18, US-19). */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val signOut: SignOut = SignOutProvider.signOut,
) : ViewModel() {

  private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
  val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

  private var observation: Job? = null

  init {
    observeProfile()
    viewModelScope.launch {
      signOut.state.collect { phase ->
        val current = _uiState.value as? ProfileUiState.ConfirmingSignOut
        when (phase) {
          SignOutState.NEEDS_DISCARD_CONFIRMATION ->
              if (current != null) {
                _uiState.value = current.copy(isSigningOut = false, pendingChanges = true)
              }
          SignOutState.CLEANING,
          SignOutState.FAILED -> {
            observation?.cancel()
            _uiState.value = ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true)
          }
          SignOutState.COMPLETE ->
              if (current != null) {
                observation?.cancel()
                _uiState.value = ProfileUiState.SignOutComplete
              }
          else -> Unit
        }
      }
    }
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
    if (!state.isSigningOut) {
      if (state.pendingChanges) signOut.staySignedIn()
      _uiState.value = state.profile
    }
  }

  /** Synchronizes first; only an explicit second confirmation may discard offline changes. */
  fun confirmSignOut(clearCredentialState: suspend () -> Unit) {
    val state = _uiState.value as? ProfileUiState.ConfirmingSignOut ?: return
    if (state.isSigningOut) return
    _uiState.value = state.copy(isSigningOut = true, failed = false)
    if (state.pendingChanges) signOut.discard(clearCredentialState)
    else signOut.begin(clearCredentialState)
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
                          hospital = profile.hospital,
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
                if (state is ProfileUiState.ConfirmingSignOut)
                    if (state.isSigningOut || profile == ProfileUiState.SignedOut) state
                    else state.copy(profile = profile)
                else profile
          }
    }
  }

  private companion object {
    const val TAG = "ProfileViewModel"
  }
}
