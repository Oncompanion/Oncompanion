package com.github.se.oncompanion.model.closecircle

import kotlinx.coroutines.flow.Flow

/** Reads a user's close circle. Use [CloseCircleRepositoryFirestore] in the app. */
interface CloseCircleRepository {

  /**
   * Emits the members of [ownerUid]'s close circle now and every time it changes, sorted by name.
   * Works offline from the local cache. The flow fails if the circle can't be read (e.g. the
   * security rules deny it).
   */
  fun observeMembers(ownerUid: String): Flow<List<CloseCircleMember>>
}
