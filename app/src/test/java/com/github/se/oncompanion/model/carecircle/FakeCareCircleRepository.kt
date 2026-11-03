// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [CareCircleRepository] for ViewModel and screen tests (no Firebase).
 *
 * Like [CareCircleRepositoryFirestore], [observeMembers] emits the current members sorted by
 * [CareCircleMember.BY_NAME], then every change, and [observeMember] emits one member (or null when
 * they aren't in the circle), then every change. [observeMembership] gives the same answer as a
 * [CareCircleMembership], except for the pairs in [unconfirmedMembers], which are
 * [CareCircleMembership.Unconfirmed] until taken out of it, like a member document the server
 * hasn't confirmed yet. [updateMember] refuses invalid details and leaves a missing member out,
 * like Firestore's `update`. Test hooks: [setMembers] changes a circle, [unconfirmedMembers]
 * simulates a server that hasn't answered, [observeError], [updateError] and [removeError] make the
 * matching calls fail, [observedOwners] records who was observed, [updated] and [removed] record
 * the writes, and [activeObservations] counts the collections still running.
 */
class FakeCareCircleRepository : CareCircleRepository {

  private val circles = MutableStateFlow<Map<String, List<CareCircleMember>>>(emptyMap())

  private val unconfirmed = MutableStateFlow<Set<Pair<String, String>>>(emptySet())

  /**
   * The (owner uid, member uid) pairs whose membership the server hasn't confirmed yet:
   * [observeMembership] emits [CareCircleMembership.Unconfirmed] for them, and the real answer once
   * a pair is taken out, like the server answering.
   */
  var unconfirmedMembers: Set<Pair<String, String>>
    get() = unconfirmed.value
    set(value) {
      unconfirmed.value = value
    }

  /** When non-null, collecting [observeMembers], [observeMember] or [observeMembership] fails. */
  var observeError: Exception? = null

  /** When non-null, [updateMember] throws it. */
  var updateError: Exception? = null

  /** When non-null, [removeMember] throws it. */
  var removeError: Exception? = null

  /** The member uid and details of every [updateMember] call, in order. */
  val updated = mutableListOf<Pair<String, CareCircleMemberDetails>>()

  /** The member uid of every [removeMember] call, in order. */
  val removed = mutableListOf<String>()

  /**
   * The owner uid of every [observeMembers], [observeMember] and [observeMembership] collection, in
   * order.
   */
  val observedOwners = mutableListOf<String>()

  /**
   * How many collections of [observeMembers], [observeMember] and [observeMembership] are running,
   * like open snapshot listeners: a collection stops counting once cancelled or failed.
   */
  var activeObservations = 0
    private set

  fun setMembers(ownerUid: String, members: List<CareCircleMember>) {
    circles.value = circles.value + (ownerUid to members)
  }

  /** The members of [ownerUid]'s circle, unsorted. */
  fun members(ownerUid: String): List<CareCircleMember> = circles.value[ownerUid].orEmpty()

  override fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>> =
      tracked(ownerUid) {
        circles.map { circle -> circle[ownerUid].orEmpty().sortedWith(CareCircleMember.BY_NAME) }
      }

  override fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?> =
      tracked(ownerUid) {
        // Like a document listener, only emits when this member changes
        circles
            .map { circle -> circle[ownerUid]?.firstOrNull { it.uid == memberUid } }
            .distinctUntilChanged()
      }

  override fun observeMembership(ownerUid: String, memberUid: String): Flow<CareCircleMembership> =
      tracked(ownerUid) {
        combine(circles, unconfirmed) { circle, unconfirmedPairs ->
              val member = circle[ownerUid]?.firstOrNull { it.uid == memberUid }
              when {
                (ownerUid to memberUid) in unconfirmedPairs -> CareCircleMembership.Unconfirmed
                member != null -> CareCircleMembership.Member(member)
                else -> CareCircleMembership.NotMember
              }
            }
            .distinctUntilChanged()
      }

  override suspend fun updateMember(
      ownerUid: String,
      memberUid: String,
      details: CareCircleMemberDetails,
  ) {
    updateError?.let { throw it }
    require(details.isValid()) { "Invalid care circle member details" }
    updated += memberUid to details
    setMembers(
        ownerUid,
        members(ownerUid).map {
          if (it.uid != memberUid) it
          else
              it.copy(
                  firstName = details.firstName,
                  familyName = details.familyName,
                  relationship = details.relationship,
                  permissions = details.permissions,
                  canAdd = details.canAdd,
              )
        },
    )
  }

  override suspend fun removeMember(ownerUid: String, memberUid: String) {
    removeError?.let { throw it }
    removed += memberUid
    setMembers(ownerUid, members(ownerUid).filterNot { it.uid == memberUid })
  }

  /** Records the collection of [ownerUid]'s circle and counts it while it runs. */
  private fun <T> tracked(ownerUid: String, source: () -> Flow<T>): Flow<T> = flow {
    observedOwners += ownerUid
    observeError?.let { throw it }
    activeObservations++
    try {
      emitAll(source())
    } finally {
      activeObservations--
    }
  }
}
