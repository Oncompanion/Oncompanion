package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [CareCircleRepository] backed by Cloud Firestore, documents at `/users/{ownerUid}/circle/{uid}`.
 *
 * Firebase is only accessed when first used, not when the repository is created: screens can create
 * it (e.g. as a ViewModel default) even where Firebase isn't initialized, like unit tests.
 */
class CareCircleRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : CareCircleRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  private val db: FirebaseFirestore by lazy(dbProvider)

  override fun observeMembers(ownerUid: String): Flow<List<CareCircleMember>> = callbackFlow {
    val registration =
        circle(ownerUid).addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
          } else if (snapshot != null) {
            // Sorted here rather than in the query, so no Firestore index is needed
            trySend(
                snapshot.documents.mapNotNull(::fromDocument).sortedWith(CareCircleMember.BY_NAME)
            )
          }
        }
    awaitClose { registration.remove() }
  }

  override fun observeMember(ownerUid: String, memberUid: String): Flow<CareCircleMember?> =
      callbackFlow {
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

  private fun circle(ownerUid: String) =
      db.collection(UserProfileRepositoryFirestore.COLLECTION)
          .document(ownerUid)
          .collection(COLLECTION)

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
    private const val TAG = "CareCircleRepository"
  }
}
