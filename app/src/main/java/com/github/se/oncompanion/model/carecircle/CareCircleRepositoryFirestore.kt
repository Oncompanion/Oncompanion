// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [CareCircleRepository] backed by Cloud Firestore, documents at `/users/{ownerUid}/circle/{uid}`.
 * Writes return once applied to the local cache; a write the server rejects is rolled back and
 * logged.
 *
 * Firebase is only accessed when first used, not when the repository is created: screens can create
 * it (e.g. as a ViewModel default) even where Firebase isn't initialized, like unit tests.
 */
class CareCircleRepositoryFirestore(
    private val dbProvider: () -> FirebaseFirestore = SessionAccess::firestore,
) : CareCircleRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  private val db: FirebaseFirestore
    get() = dbProvider()

  override fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>> =
      SessionAccess.snapshots {
        val registration =
            circle(ownerUid).addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else if (snapshot != null) {
                // Sorted here rather than in the query, so no Firestore index is needed
                trySend(
                    snapshot.documents
                        .mapNotNull(::fromDocument)
                        .sortedWith(CareCircleMember.BY_NAME)
                )
              }
            }
        awaitClose { registration.remove() }
      }

  override fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?> =
      SessionAccess.snapshots {
        val registration =
            circle(ownerUid).document(memberUid).addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else if (snapshot != null) {
                // null means either a missing document (never added or removed, not logged) or a
                // malformed one (logged by fromDocument): callers can't tell them apart on purpose,
                // as both mean there is no member to show
                trySend(if (snapshot.exists()) fromDocument(snapshot) else null)
              }
            }
        awaitClose { registration.remove() }
      }

  override suspend fun updateMember(
      ownerUid: String,
      memberUid: String,
      details: CareCircleMemberDetails,
  ): Unit = SessionAccess.operation {
    require(details.isValid()) { "Invalid care circle member details" }
    // update, never set: a member removed on another device must not come back
    circle(ownerUid).document(memberUid).update(detailFields(details)).addOnFailureListener { e ->
      logRejected("update of member $memberUid", e)
    }
  }

  override suspend fun removeMember(ownerUid: String, memberUid: String): Unit =
      SessionAccess.operation {
        // One batch, so going offline halfway can't leave the caregiver a link to a circle they
        // left
        db.batch()
            .delete(circle(ownerUid).document(memberUid))
            .delete(
                users()
                    .document(memberUid)
                    .collection(CareCircleInviteRepositoryFirestore.PATIENTS_COLLECTION)
                    .document(ownerUid)
            )
            .commit()
            .addOnFailureListener { e -> logRejected("removal of member $memberUid", e) }
      }

  private fun users() = db.collection(UserProfileRepositoryFirestore.COLLECTION)

  private fun circle(ownerUid: String) = users().document(ownerUid).collection(COLLECTION)

  private fun logRejected(what: String, e: Exception) {
    Log.e(TAG, "The server rejected the $what; it was rolled back", e)
  }

  private fun fromDocument(doc: DocumentSnapshot): CareCircleMember? {
    val firstName = doc.getString(FIELD_FIRST_NAME)
    if (firstName.isNullOrBlank()) {
      Log.e(TAG, "Care circle member ${doc.id} has no first name, skipped")
      return null
    }
    val permissions = (doc.get(FIELD_PERMISSIONS) as? List<*>).orEmpty().filterIsInstance<String>()
    return CareCircleMember(
        uid = doc.id,
        firstName = firstName,
        familyName = doc.getString(FIELD_FAMILY_NAME),
        relationship = Relationship.fromName(doc.getString(FIELD_RELATIONSHIP)),
        permissions = CarePermission.fromNames(permissions),
        // Read leniently: a field of the wrong type is treated as missing (and logged)
        email = doc.getLenient<String>(FIELD_EMAIL),
        addedAt = doc.getLenient<Timestamp>(FIELD_ADDED_AT)?.toInstant(),
        inviteCode = doc.getLenient<String>(FIELD_INVITE_CODE),
    )
  }

  /**
   * Returns [field] as a [T], or null if it is missing or has another type. A wrong type is logged:
   * the member is still shown, but the bad data shouldn't go unnoticed.
   */
  private inline fun <reified T> DocumentSnapshot.getLenient(field: String): T? {
    val value = get(field) ?: return null
    if (value !is T) {
      Log.w(TAG, "Care circle member $id has a $field of the wrong type, ignored")
      return null
    }
    return value
  }

  companion object {
    /** Subcollection of `/users/{uid}`. */
    const val COLLECTION = "circle"
    const val FIELD_FIRST_NAME = "firstName"
    const val FIELD_FAMILY_NAME = "familyName"
    const val FIELD_RELATIONSHIP = "relationship"
    const val FIELD_PERMISSIONS = "permissions"
    const val FIELD_EMAIL = "email"
    const val FIELD_ADDED_AT = "addedAt"
    const val FIELD_INVITE_CODE = "inviteCode"
    private const val TAG = "CareCircleRepository"

    /** The fields of [details], as stored on a member and on an invite. */
    internal fun detailFields(details: CareCircleMemberDetails): Map<String, Any?> =
        mapOf(
            FIELD_FIRST_NAME to details.firstName,
            FIELD_FAMILY_NAME to details.familyName,
            FIELD_RELATIONSHIP to details.relationship.name,
            // In display order, so a member's list equals its invite's
            FIELD_PERMISSIONS to
                CarePermission.entries.filter { it in details.permissions }.map { it.name },
        )
  }
}
