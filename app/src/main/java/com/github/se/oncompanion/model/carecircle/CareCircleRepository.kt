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
}
