// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FAMILY_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FIRST_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_PERMISSIONS
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_RELATIONSHIP
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * [CareCircleInviteRepository] backed by Cloud Firestore, documents at `/invites/{inviteCode}`.
 *
 * Like [com.github.se.oncompanion.model.symptom.SymptomRepositoryFirestore], writes return as soon
 * as they are applied to the local cache, so inviting works offline. If the server later rejects a
 * write, Firestore rolls it back and the rejection is logged; [CareCircleMemberDetails.isValid]
 * mirrors the rules so this shouldn't happen. The detail fields have the same names as on a
 * [CareCircleMember], so the rules can check a member copies its invite.
 */
class CareCircleInviteRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : CareCircleInviteRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override suspend fun createInvite(
      ownerUid: String,
      ownerFirstName: String,
      details: CareCircleMemberDetails,
      email: String?,
  ): CareCircleInvite {
    requireValid(details, email)
    val invite =
        CareCircleInvite(
            inviteCode = CareCircleInvite.generateInviteCode(),
            ownerUid = ownerUid,
            ownerFirstName = ownerFirstName,
            details = details,
            email = email,
            isSynced = false,
        )
    // A set on an existing invite code counts as an update for the rules, so a (very unlikely)
    // collision is rejected and logged instead of overwriting another invite
    invites().document(invite.inviteCode).set(newInviteFields(invite)).addOnFailureListener { e ->
      logRejected("invite ${invite.inviteCode}", e)
    }
    return invite
  }

  override fun observePendingInvites(ownerUid: String): Flow<List<CareCircleInvite>> =
      callbackFlow {
        // Two equality filters need no composite index; sorted here, so no orderBy index either
        val registration =
            invites()
                .whereEqualTo(FIELD_OWNER_UID, ownerUid)
                .whereEqualTo(FIELD_REDEEMER_UID, null)
                // Metadata changes too, so isSynced turns true once the server has the invite
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                  if (error != null) {
                    close(error)
                  } else if (snapshot != null) {
                    trySend(
                        snapshot.documents
                            .mapNotNull(::fromDocument)
                            .sortedWith(CareCircleInvite.BY_NAME)
                    )
                  }
                }
        awaitClose { registration.remove() }
      }
      .distinctUntilChanged()

  override fun observeInvite(inviteCode: String): Flow<CareCircleInvite?> = callbackFlow {
    val registration =
        invites().document(inviteCode).addSnapshotListener(MetadataChanges.INCLUDE) {
            snapshot,
            error ->
          if (error != null) {
            close(error)
          } else if (snapshot != null) {
            // A used invite is no longer pending: callers see it like a cancelled one
            val invite = if (snapshot.exists()) fromDocument(snapshot) else null
            trySend(invite?.takeIf { it.redeemerUid == null })
          }
        }
    awaitClose { registration.remove() }
  }
      .distinctUntilChanged()

  override suspend fun updateInvite(
      inviteCode: String,
      details: CareCircleMemberDetails,
      email: String?,
  ) {
    requireValid(details, email)
    // update, never set: a cancelled invite must not come back
    invites()
        .document(inviteCode)
        .update(detailFields(details) + (FIELD_EMAIL to email))
        .addOnFailureListener { e -> logRejected("update of invite $inviteCode", e) }
  }

  override suspend fun cancelInvite(inviteCode: String) {
    invites().document(inviteCode).delete().addOnFailureListener { e ->
      logRejected("cancellation of invite $inviteCode", e)
    }
  }

  override suspend fun renewInvite(expired: CareCircleInvite): CareCircleInvite {
    requireValid(expired.details, expired.email)
    val renewed =
        expired.copy(
            inviteCode = CareCircleInvite.generateInviteCode(),
            createdAt = null,
            redeemerUid = null,
            isSynced = false,
        )
    // One batch, so the patient never ends up with both invite codes or neither
    db.batch()
        .set(invites().document(renewed.inviteCode), newInviteFields(renewed))
        .delete(invites().document(expired.inviteCode))
        .commit()
        .addOnFailureListener { e -> logRejected("renewal of invite ${expired.inviteCode}", e) }
    return renewed
  }

  private fun invites() = db.collection(COLLECTION)

  private fun requireValid(details: CareCircleMemberDetails, email: String?) {
    require(details.isValid()) { "Invalid care circle member details" }
    require(email == null || CareCircleInvite.isValidEmail(email)) { "Invalid email" }
  }

  private fun detailFields(details: CareCircleMemberDetails): Map<String, Any?> =
      mapOf(
          FIELD_FIRST_NAME to details.firstName,
          FIELD_FAMILY_NAME to details.familyName,
          FIELD_RELATIONSHIP to details.relationship.name,
          // In display order, so a member's list equals its invite's
          FIELD_PERMISSIONS to
              CarePermission.entries.filter { it in details.permissions }.map { it.name },
      )

  /** All the fields of a new, unused invite; the server sets the creation time. */
  private fun newInviteFields(invite: CareCircleInvite): Map<String, Any?> =
      detailFields(invite.details) +
          mapOf(
              FIELD_OWNER_UID to invite.ownerUid,
              FIELD_OWNER_FIRST_NAME to invite.ownerFirstName,
              FIELD_EMAIL to invite.email,
              FIELD_CREATED_AT to FieldValue.serverTimestamp(),
              FIELD_REDEEMER_UID to null,
          )

  private fun logRejected(what: String, e: Exception) {
    Log.e(TAG, "The server rejected the $what; it was rolled back", e)
  }

  /** Reads leniently like the member mapping: only a missing name or owner skips the invite. */
  private fun fromDocument(doc: DocumentSnapshot): CareCircleInvite? {
    val firstName = doc.getString(FIELD_FIRST_NAME)
    val ownerUid = doc.getString(FIELD_OWNER_UID)
    if (firstName.isNullOrBlank() || ownerUid.isNullOrBlank()) {
      Log.e(TAG, "Care circle invite ${doc.id} has no first name or owner, skipped")
      return null
    }
    val permissions = (doc.get(FIELD_PERMISSIONS) as? List<*>).orEmpty().filterIsInstance<String>()
    return CareCircleInvite(
        inviteCode = doc.id,
        ownerUid = ownerUid,
        ownerFirstName = doc.get(FIELD_OWNER_FIRST_NAME) as? String ?: "",
        details =
            CareCircleMemberDetails(
                firstName = firstName,
                familyName = doc.get(FIELD_FAMILY_NAME) as? String,
                relationship = Relationship.fromName(doc.get(FIELD_RELATIONSHIP) as? String),
                permissions = CarePermission.fromNames(permissions),
            ),
        email = doc.get(FIELD_EMAIL) as? String,
        // Pending server timestamps read as null until the write reaches the server
        createdAt = (doc.get(FIELD_CREATED_AT) as? Timestamp)?.toInstant(),
        redeemerUid = doc.get(FIELD_REDEEMER_UID) as? String,
        isSynced = !doc.metadata.hasPendingWrites(),
    )
  }

  companion object {
    /** Top-level collection: the invite code is the secret, not the path. */
    const val COLLECTION = "invites"
    const val FIELD_OWNER_UID = "ownerUid"
    const val FIELD_OWNER_FIRST_NAME = "ownerFirstName"
    const val FIELD_EMAIL = "email"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_REDEEMER_UID = "redeemerUid"
    private const val TAG = "CareCircleInviteRepo"
  }
}
