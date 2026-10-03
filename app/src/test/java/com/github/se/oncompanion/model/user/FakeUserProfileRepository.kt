package com.github.se.oncompanion.model.user

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [UserProfileRepository] for ViewModel unit tests (no Firebase).
 *
 * Follows the same contract as [UserProfileRepositoryFirestore]:
 * - [createProfile] requires [UserProfile.isValid] (else [IllegalArgumentException]) and stores the
 *   profile with `createdAt = clock()`, ignoring the given `createdAt`.
 * - [updateProfile] requires [UserProfile.isValid] and only changes `firstName`, `familyName` and
 *   `cancerType` of an existing profile. The stored `role` and `createdAt` never change. It does
 *   nothing if no profile exists for that uid.
 * - [getProfile] returns the stored profile, or null.
 * - [observeProfile] emits the current value, then every change.
 *
 * Test hooks: [getProfileError] and [writeError] simulate unexpected failures, [profiles] exposes a
 * snapshot of the stored data.
 *
 * @param clock source of the `createdAt` timestamp set on creation (the "server time").
 */
class FakeUserProfileRepository(private val clock: () -> Instant = Instant::now) :
    UserProfileRepository {

  private val store = MutableStateFlow<Map<String, UserProfile>>(emptyMap())

  /**
   * When non-null, [getProfile] throws it and [observeProfile] fails with it (checked when
   * collection starts).
   */
  var getProfileError: Exception? = null

  /** When non-null, [createProfile] and [updateProfile] throw it and store nothing. */
  var writeError: Exception? = null

  /** Snapshot of the stored profiles, keyed by uid. */
  val profiles: Map<String, UserProfile>
    get() = store.value

  /** Inserts [profile] as-is (including its `createdAt`), bypassing validation. Test setup only. */
  fun seed(profile: UserProfile) {
    store.update { it + (profile.uid to profile) }
  }

  override suspend fun getProfile(uid: String): UserProfile? {
    getProfileError?.let { throw it }
    return store.value[uid]
  }

  override fun observeProfile(uid: String): Flow<UserProfile?> = flow {
    getProfileError?.let { throw it }
    emitAll(store.map { it[uid] }.distinctUntilChanged())
  }

  override suspend fun createProfile(profile: UserProfile) {
    writeError?.let { throw it }
    require(profile.isValid()) { "Invalid profile: $profile" }
    store.update { it + (profile.uid to profile.copy(createdAt = clock())) }
  }

  override suspend fun updateProfile(profile: UserProfile) {
    writeError?.let { throw it }
    require(profile.isValid()) { "Invalid profile: $profile" }
    store.update { current ->
      val existing = current[profile.uid] ?: return@update current
      current +
          (profile.uid to
              existing.copy(
                  firstName = profile.firstName,
                  familyName = profile.familyName,
                  cancerType = profile.cancerType,
              ))
    }
  }
}
