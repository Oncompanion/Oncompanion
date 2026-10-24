// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_ADDED_AT
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FAMILY_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_FIRST_NAME
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_INVITE_CODE
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_PERMISSIONS
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.FIELD_RELATIONSHIP
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.detailFields
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import java.time.Instant
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await

/**
 * [CareCircleInviteRepository] backed by Cloud Firestore, documents at `/invites/{inviteCode}`.
 * Redeeming an invite deletes it and writes the member at
 * `/users/{patientUid}/circle/{caregiverUid}` and the caregiver's [JoinedCareCircle] at
 * `/users/{caregiverUid}/patients/{patientUid}`.
 *
 * Writes don't await Firestore's tasks on purpose: those only complete once the server confirms,
 * which would block offline. Each write is queued on the device, where reads and listeners see it
 * at once, and the function returns. If the server later rejects it, Firestore rolls it back and
 * the rejection is only logged; [CareCircleMemberDetails.isValid] mirrors the rules so this
 * shouldn't happen. [redeem] is the exception: it is a transaction, which needs the server. The
 * detail fields have the same names as on a [CareCircleMember], so the rules can check a member
 * copies its invite.
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

  override fun observeInvite(ownerUid: String, inviteCode: String): Flow<CareCircleInvite?> =
      callbackFlow {
        // All the owner's invites (a handful), not a document listener: the rules refuse to read a
        // missing invite, so nobody learns which invite codes existed. Filtering on the document ID
        // in the query would be checked against that missing document too.
        val registration =
            invites().whereEqualTo(FIELD_OWNER_UID, ownerUid).addSnapshotListener(
                MetadataChanges.INCLUDE
            ) { snapshot, error ->
              if (error != null) {
                close(error)
              } else if (snapshot != null) {
                // Used invites are deleted, so a used invite reads like a cancelled one
                trySend(snapshot.documents.firstOrNull { it.id == inviteCode }?.let(::fromDocument))
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

  override suspend fun redeem(
      inviteCode: String,
      caregiverUid: String,
      caregiverEmail: String?,
  ): RedeemResult {
    val code = CareCircleInvite.normalizeInviteCode(inviteCode)
    // A mistyped invite code is answered at once, without the network
    if (!CareCircleInvite.isWellFormedInviteCode(code)) return RedeemResult.InvalidOrUsed
    return try {
      val result =
          db.runTransaction { transaction ->
                redeemIn(transaction, code, caregiverUid, caregiverEmail)
              }
              .await()
      if (result is RedeemResult.Joined) refreshJoinedCircles(caregiverUid)
      result
    } catch (e: FirebaseFirestoreException) {
      // Only a confirmed connection problem is reported as offline. Any rejection, such as reading
      // an unknown, used or expired invite, means the invite code can't be used. The invite code
      // isn't logged: it works like a password.
      Log.w(TAG, "Redeeming an invite failed with ${e.code}", e)
      if (e.code == FirebaseFirestoreException.Code.UNAVAILABLE) RedeemResult.Offline
      else RedeemResult.InvalidOrUsed
    }
  }

  override suspend fun hasJoinedCircle(caregiverUid: String): Boolean {
    // The phone's copy may not know a circle joined a moment ago: a transaction doesn't update it
    val snapshot =
        try {
          patients(caregiverUid).get(Source.SERVER).await()
        } catch (e: FirebaseFirestoreException) {
          if (e.code != FirebaseFirestoreException.Code.UNAVAILABLE) throw e
          patients(caregiverUid).get(Source.CACHE).await()
        }
    return !snapshot.isEmpty
  }

  /**
   * Reads [caregiverUid]'s circles from the server once, so the phone's copy knows the circle just
   * joined, also at a later start offline. Only a convenience: a failure is logged and ignored.
   */
  private suspend fun refreshJoinedCircles(caregiverUid: String) {
    try {
      patients(caregiverUid).get(Source.SERVER).await()
    } catch (e: FirebaseFirestoreException) {
      Log.w(TAG, "Couldn't refresh the joined circles after joining", e)
    }
  }

  override fun observeJoinedCircles(caregiverUid: String): Flow<List<JoinedCareCircle>> =
      callbackFlow {
        val registration =
            patients(caregiverUid).addSnapshotListener { snapshot, error ->
              if (error != null) close(error)
              else if (snapshot != null) trySend(snapshot.documents.map(::joinedFromDocument))
            }
        awaitClose { registration.remove() }
      }
      .distinctUntilChanged()

  /**
   * Checks the invite [inviteCode] and the caregiver's membership on the server, then writes the
   * member and the caregiver's [JoinedCareCircle] and deletes the invite in [transaction]. A
   * transaction is never applied locally first nor queued offline, so the caregiver never sees a
   * join that the server later undoes. The checks give an answer the caregiver understands; the
   * rules are the real guard.
   */
  private fun redeemIn(
      transaction: Transaction,
      inviteCode: String,
      caregiverUid: String,
      caregiverEmail: String?,
  ): RedeemResult {
    val doc = transaction.get(invites().document(inviteCode))
    val invite = if (doc.exists()) fromDocument(doc) else null
    if (invite == null || invite.ownerUid == caregiverUid || invite.isExpired(Instant.now())) {
      return RedeemResult.InvalidOrUsed
    }
    val member = memberDoc(invite.ownerUid, caregiverUid)
    if (transaction.get(member).exists()) {
      return RedeemResult.AlreadyMember(invite.ownerFirstName)
    }
    transaction
        .set(
            member,
            detailFields(invite.details) +
                mapOf(
                    FIELD_EMAIL to caregiverEmail,
                    FIELD_INVITE_CODE to inviteCode,
                    FIELD_ADDED_AT to FieldValue.serverTimestamp(),
                ),
        )
        .set(
            users()
                .document(caregiverUid)
                .collection(PATIENTS_COLLECTION)
                .document(invite.ownerUid),
            mapOf(
                FIELD_PATIENT_FIRST_NAME to invite.ownerFirstName,
                FIELD_INVITE_CODE to inviteCode,
                FIELD_JOINED_AT to FieldValue.serverTimestamp(),
            ),
        )
        // Used up: the invite code can't be used again, even after the member is removed
        .delete(invites().document(inviteCode))
    return RedeemResult.Joined(invite.ownerUid, invite.ownerFirstName)
  }

  private fun invites() = db.collection(COLLECTION)

  private fun users() = db.collection(UserProfileRepositoryFirestore.COLLECTION)

  private fun patients(caregiverUid: String) =
      users().document(caregiverUid).collection(PATIENTS_COLLECTION)

  private fun memberDoc(ownerUid: String, memberUid: String) =
      users()
          .document(ownerUid)
          .collection(CareCircleRepositoryFirestore.COLLECTION)
          .document(memberUid)

  private fun requireValid(details: CareCircleMemberDetails, email: String?) {
    require(details.isValid()) { "Invalid care circle member details" }
    require(email == null || CareCircleInvite.isValidEmail(email)) { "Invalid email" }
  }

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
  /** The patient's first name may be missing in a damaged document: shown empty, not dropped. */
  private fun joinedFromDocument(doc: DocumentSnapshot) =
      JoinedCareCircle(
          patientUid = doc.id,
          patientFirstName = doc.get(FIELD_PATIENT_FIRST_NAME) as? String ?: "",
          joinedAt = (doc.get(FIELD_JOINED_AT) as? Timestamp)?.toInstant(),
      )

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
    /** Subcollection of `/users/{caregiverUid}`: the caregiver's [JoinedCareCircle]s. */
    const val PATIENTS_COLLECTION = "patients"
    const val FIELD_PATIENT_FIRST_NAME = "patientFirstName"
    const val FIELD_JOINED_AT = "joinedAt"
    private const val TAG = "CareCircleInviteRepo"
  }
}
