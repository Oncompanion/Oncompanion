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
}

/** Observes the authenticated user's profile (US-18, read-only Profile screen). */
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
    observeProfile()
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
          .collect { _uiState.value = it }
    }
  }

  private companion object {
    const val TAG = "ProfileViewModel"
  }
}
