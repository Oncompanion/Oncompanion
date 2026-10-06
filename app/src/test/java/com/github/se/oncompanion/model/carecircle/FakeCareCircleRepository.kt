package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [CareCircleRepository] for ViewModel and screen tests (no Firebase).
 *
 * Like [CareCircleRepositoryFirestore], [observeMembers] emits the current members sorted by name,
 * then every change. Test hooks: [setMembers] changes a circle, [observeError] makes the next
 * [observeMembers] collections fail, and [observedOwners] records who was observed.
 */
class FakeCareCircleRepository : CareCircleRepository {

  private val circles = MutableStateFlow<Map<String, List<CareCircleMember>>>(emptyMap())

  /** When non-null, collecting [observeMembers] fails with it. */
  var observeError: Exception? = null

  /** The owner uid of every [observeMembers] collection, in order. */
  val observedOwners = mutableListOf<String>()

  fun setMembers(ownerUid: String, members: List<CareCircleMember>) {
    circles.value = circles.value + (ownerUid to members)
  }

  override fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>> = flow {
    observedOwners += ownerUid
    observeError?.let { throw it }
    emitAll(
        circles.map { circle -> circle[ownerUid].orEmpty().sortedBy { it.fullName.lowercase() } }
    )
  }
}
