// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * In-memory [CareCircleInviteRepository] for ViewModel and screen tests (no Firebase).
 *
 * Like [CareCircleInviteRepositoryFirestore], [observePendingInvites] emits the owner's unused
 * invites sorted by [CareCircleInvite.BY_NAME], then every change, [observeInvite] emits one unused
 * invite (or null), and writes refuse invalid details or emails. New invites are stored with
 * `isSynced = true` (the server has them); call [setInvites] to test unsynced ones. Test hooks:
 * [setInvites] replaces every invite, [observeError], [createError], [updateError] and
 * [cancelError] make the matching calls fail, [created] records the invites created (including
 * renewed ones), and [nextInviteCode] picks the next invite code.
 */
class FakeCareCircleInviteRepository : CareCircleInviteRepository {

  private val invites = MutableStateFlow<Map<String, CareCircleInvite>>(emptyMap())

  /** When non-null, collecting [observePendingInvites] or [observeInvite] fails with it. */
  var observeError: Exception? = null

  /** When non-null, [createInvite] and [renewInvite] throw it. */
  var createError: Exception? = null

  /** When non-null, [updateInvite] throws it. */
  var updateError: Exception? = null

  /** When non-null, [cancelInvite] throws it. */
  var cancelError: Exception? = null

  /** Every invite created by [createInvite] or [renewInvite], in order. */
  val created = mutableListOf<CareCircleInvite>()

  /** Returns the invite code of the next created invite; random ones by default. */
  var nextInviteCode: () -> String = { CareCircleInvite.generateInviteCode() }

  /** The invite stored with [inviteCode], whatever its state, or null. */
  fun invite(inviteCode: String): CareCircleInvite? = invites.value[inviteCode]

  /** Replaces every stored invite with [list]. */
  fun setInvites(list: List<CareCircleInvite>) {
    invites.value = list.associateBy { it.inviteCode }
  }

  override suspend fun createInvite(
      ownerUid: String,
      ownerFirstName: String,
      details: CareCircleMemberDetails,
      email: String?,
  ): CareCircleInvite {
    createError?.let { throw it }
    requireValid(details, email)
    return store(
        CareCircleInvite(nextInviteCode(), ownerUid, ownerFirstName, details, email = email)
    )
  }

  override fun observePendingInvites(ownerUid: String): Flow<List<CareCircleInvite>> = observed {
    invites
        .map { all ->
          all.values
              .filter { it.ownerUid == ownerUid && it.redeemerUid == null }
              .sortedWith(CareCircleInvite.BY_NAME)
        }
        .distinctUntilChanged()
  }

  override fun observeInvite(inviteCode: String): Flow<CareCircleInvite?> = observed {
    invites
        .map { it[inviteCode]?.takeIf { invite -> invite.redeemerUid == null } }
        .distinctUntilChanged()
  }

  override suspend fun updateInvite(
      inviteCode: String,
      details: CareCircleMemberDetails,
      email: String?,
  ) {
    updateError?.let { throw it }
    requireValid(details, email)
    // Like Firestore's update (and the rules), a missing or used invite is left unchanged
    val invite = invites.value[inviteCode]?.takeIf { it.redeemerUid == null } ?: return
    invites.value += inviteCode to invite.copy(details = details, email = email)
  }

  override suspend fun cancelInvite(inviteCode: String) {
    cancelError?.let { throw it }
    invites.value -= inviteCode
  }

  override suspend fun renewInvite(expired: CareCircleInvite): CareCircleInvite {
    createError?.let { throw it }
    requireValid(expired.details, expired.email)
    invites.value -= expired.inviteCode
    return store(expired.copy(inviteCode = nextInviteCode(), createdAt = null, redeemerUid = null))
  }

  private fun store(invite: CareCircleInvite): CareCircleInvite {
    invites.value += invite.inviteCode to invite
    created += invite
    return invite
  }

  private fun requireValid(details: CareCircleMemberDetails, email: String?) {
    require(details.isValid()) { "Invalid care circle member details" }
    require(email == null || CareCircleInvite.isValidEmail(email)) { "Invalid email" }
  }

  private fun <T> observed(source: () -> Flow<T>): Flow<T> = flow {
    observeError?.let { throw it }
    emitAll(source())
  }
}
