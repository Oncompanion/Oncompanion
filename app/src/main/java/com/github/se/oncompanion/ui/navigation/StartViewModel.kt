// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.navigation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryProvider
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides the first screen when the app starts (#30, US-2), while the splash screen is shown:
 * - nobody signed in → sign-in ([Route.AUTH])
 * - signed in with a profile → the Overview ([Route.OVERVIEW]), except a caregiver who hasn't
 *   joined a care circle yet → the join screen ([Route.JOIN_CIRCLE])
 * - signed in without a profile (onboarding not finished) → onboarding ([Route.ONBOARDING])
 *
 * It works offline: Firebase remembers the session and Firestore reads the profile from its local
 * cache. The profile saved on the phone is checked first, since it answers at once, while the
 * normal read can wait for a slow server. If the profile or a caregiver's circles can't be read in
 * time, the user goes to the Overview: onboarding would try to create a profile that may already
 * exist.
 *
 * @param profileTimeoutMillis how long to wait for the profile (and for a caregiver's circles)
 *   before choosing the Overview
 */
class StartViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val profileTimeoutMillis: Long = DEFAULT_PROFILE_TIMEOUT_MILLIS,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
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
    // A list, so that "read, no profile" (empty) differs from "couldn't read in time" (null)
    val profile =
        try {
          withTimeoutOrNull(profileTimeoutMillis) {
            // Only ask the server when the phone doesn't know whether the profile exists
            listOfNotNull(cachedProfile(user.uid) ?: profileRepository.getProfile(user.uid))
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Log.w(TAG, "Couldn't read the profile at startup", e)
          null
        }
    return when {
      profile == null -> Route.OVERVIEW
      profile.isEmpty() -> Route.ONBOARDING
      profile.single().role == Role.CAREGIVER && !hasJoinedCircle(user.uid) -> Route.JOIN_CIRCLE
      else -> Route.OVERVIEW
    }
  }

  /** The profile saved on the phone; `null` when the cache doesn't know or fails. */
  private suspend fun cachedProfile(uid: String): UserProfile? =
      try {
        profileRepository.getCachedProfile(uid)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the cached profile at startup", e)
        null
      }

  /** Whether the caregiver joined a circle; assumed when it can't be read in time. */
  private suspend fun hasJoinedCircle(uid: String): Boolean =
      try {
        withTimeoutOrNull(profileTimeoutMillis) { inviteRepository.hasJoinedCircle(uid) } ?: true
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the caregiver's circles at startup", e)
        true
      }

  companion object {
    const val DEFAULT_PROFILE_TIMEOUT_MILLIS = 5_000L
    private const val TAG = "StartViewModel"
  }
}
