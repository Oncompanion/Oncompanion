package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow

/** Reads a user's care circle. Use [CareCircleRepositoryFirestore] in the app. */
interface CareCircleRepository {

  /**
   * Emits the members of [ownerUid]'s care circle now and every time it changes, sorted by name.
   * Works offline from the local cache. The flow fails if the circle can't be read (e.g. the
   * security rules deny it).
   */
  fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>>

  /**
   * Emits the member [memberUid] of [ownerUid]'s care circle now and every time it changes, or null
   * when they aren't in the circle (never added, removed, or stored without a first name). Works
   * offline from the local cache. The flow fails if the member can't be read (e.g. the security
   * rules deny it).
   */
  fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?>
}
