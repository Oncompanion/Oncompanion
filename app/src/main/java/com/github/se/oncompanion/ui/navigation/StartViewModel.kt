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
 * - signed in with a profile → the Overview ([Route.OVERVIEW]); for a caregiver, "Whose care?"
 *   ([Route.WHOSE_CARE]), or the join screen ([Route.JOIN_CIRCLE]) until they join a circle
 * - signed in without a profile (onboarding not finished) → onboarding ([Route.ONBOARDING])
 *
 * It works offline: Firebase remembers the session and Firestore reads the profile from its local
 * cache. The profile saved on the phone is checked first, since it answers at once, while the
 * normal read can wait for a slow server. If the profile or a caregiver's circles can't be read in
 * time, a known caregiver goes to "Whose care?", which reads their circles itself, and anyone else
 * to the Overview: onboarding would try to create a profile that may already exist.
 *
 * @param profileTimeoutMillis how long to wait for the profile and, for a caregiver, their circles
 *   (both together) before choosing without them
 */
class StartViewModel(
    private val authRepository: AuthRepository = AuthRepositoryProvider.repository,
    private val profileRepository: UserProfileRepository = UserProfileRepositoryProvider.repository,
    private val profileTimeoutMillis: Long = DEFAULT_PROFILE_TIMEOUT_MILLIS,
    private val inviteRepository: CareCircleInviteRepository =
        CareCircleInviteRepositoryProvider.repository,
) : ViewModel() {

  private val _startRoute = MutableStateFlow<String?>(null)

  // The role, once the profile is read: a caregiver never falls back to the patient Overview
  private var knownRole: Role? = null

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
    // One timeout for the profile and a caregiver's circles together, so the splash never waits
    // longer than it
    return try {
      withTimeoutOrNull(profileTimeoutMillis) { routeFor(user.uid) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "Couldn't read the profile at startup", e)
      null
    } ?: if (knownRole == Role.CAREGIVER) Route.WHOSE_CARE else Route.OVERVIEW
  }

  /** Where [uid] starts, from their profile and, for a caregiver, their circles. */
  private suspend fun routeFor(uid: String): String {
    // Only ask the server when the phone doesn't know whether the profile exists
    val profile = cachedProfile(uid) ?: profileRepository.getProfile(uid)
    knownRole = profile?.role
    return when {
      profile == null -> Route.ONBOARDING
      profile.role != Role.CAREGIVER -> Route.OVERVIEW
      hasJoinedCircle(uid) -> Route.WHOSE_CARE
      else -> Route.JOIN_CIRCLE
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

  /** Whether the caregiver joined a circle; assumed when it can't be read. */
  private suspend fun hasJoinedCircle(uid: String): Boolean =
      try {
        inviteRepository.hasJoinedCircle(uid)
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
