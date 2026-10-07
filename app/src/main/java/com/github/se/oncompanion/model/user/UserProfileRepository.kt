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
   * server. Returns as soon as the profile is saved on the device (also offline); it reaches the
   * server when there is a connection.
   *
   * @throws IllegalArgumentException if the profile is not [UserProfile.isValid]
   */
  suspend fun createProfile(profile: UserProfile)

  /**
   * Updates the names and cancer type of an existing profile. The role and [UserProfile.createdAt]
   * can't change after onboarding: [UserProfile.role] is ignored. Returns as soon as the change is
   * saved on the device (also offline).
   *
   * @throws IllegalArgumentException if the profile is not [UserProfile.isValid]
   */
  suspend fun updateProfile(profile: UserProfile)
}
