package com.github.se.oncompanion.model.user

import kotlinx.coroutines.flow.Flow

/** Reads and writes user profiles. Use [UserProfileRepositoryFirestore] in the app. */
interface UserProfileRepository {

  /**
   * Returns the profile of [uid], or `null` if it doesn't exist (onboarding not finished). Works
   * offline from the local cache once the profile has been loaded or written on this device.
   */
  suspend fun getProfile(uid: String): UserProfile?

  /** Emits the profile of [uid] (or `null`) now and every time it changes. */
  fun observeProfile(uid: String): Flow<UserProfile?>

  /**
   * Creates the profile at the end of onboarding. [UserProfile.createdAt] is ignored and set by the
   * server.
   *
   * @throws IllegalArgumentException if the profile is not [UserProfile.isValid]
   */
  suspend fun createProfile(profile: UserProfile)

  /**
   * Updates role, names and cancer type of an existing profile. [UserProfile.createdAt] is never
   * changed.
   *
   * @throws IllegalArgumentException if the profile is not [UserProfile.isValid]
   */
  suspend fun updateProfile(profile: UserProfile)
}
