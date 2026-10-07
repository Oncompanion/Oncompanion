package com.github.se.oncompanion.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryFirebase
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides the first screen when the app starts (#30, US-2), while the splash screen is shown:
 * - nobody signed in → sign-in ([Route.AUTH])
 * - signed in with a profile → the Overview ([Route.OVERVIEW])
 * - signed in without a profile (onboarding not finished) → onboarding ([Route.ONBOARDING])
 *
 * It works offline: Firebase remembers the session and Firestore reads the profile from its local
 * cache. The profile saved on the phone is checked first, since it answers at once, while the
 * normal read can wait for a slow server. If the profile can't be read in time, the user goes to
 * the Overview: onboarding would try to create a profile that may already exist.
 *
 * @param profileTimeoutMillis how long to wait for the profile before choosing the Overview
 */
class StartViewModel(
    private val authRepository: AuthRepository = AuthRepositoryFirebase(),
    private val profileRepository: UserProfileRepository = UserProfileRepositoryFirestore(),
    private val profileTimeoutMillis: Long = DEFAULT_PROFILE_TIMEOUT_MILLIS,
) : ViewModel() {

  private val _startRoute = MutableStateFlow<String?>(null)

  /** The [Route] to start on, or `null` while it is being decided. */
  val startRoute: StateFlow<String?> = _startRoute.asStateFlow()

  init {
    viewModelScope.launch { _startRoute.value = decideStartRoute() }
  }

  private suspend fun decideStartRoute(): String {
    val user =
        try {
          authRepository.currentUser
        } catch (e: Exception) {
          // E.g. Firebase not initialized (unit tests): treat as signed out
          Log.w(TAG, "Couldn't read the signed-in user", e)
          null
        } ?: return Route.AUTH
    val hasProfile =
        try {
          withTimeoutOrNull(profileTimeoutMillis) {
            // Only ask the server when the phone doesn't know whether the profile exists
            hasCachedProfile(user.uid) || profileRepository.getProfile(user.uid) != null
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't read the profile at startup", e)
          null
        }
    return if (hasProfile == false) Route.ONBOARDING else Route.OVERVIEW
  }

  /** Whether the profile is saved on the phone; `false` when the cache doesn't know or fails. */
  private suspend fun hasCachedProfile(uid: String): Boolean =
      try {
        profileRepository.getCachedProfile(uid) != null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the cached profile at startup", e)
        false
      }

  companion object {
    const val DEFAULT_PROFILE_TIMEOUT_MILLIS = 5_000L
    private const val TAG = "StartViewModel"
  }
}
