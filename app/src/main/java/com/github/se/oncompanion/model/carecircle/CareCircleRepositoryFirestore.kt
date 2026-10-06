package com.github.se.oncompanion.model.carecircle

import android.util.Log
import com.github.se.oncompanion.model.user.UserProfileRepositoryFirestore
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
        db.collection(UserProfileRepositoryFirestore.COLLECTION)
            .document(ownerUid)
            .collection(COLLECTION)
            .addSnapshotListener { snapshot, error ->
              if (error != null) {
                close(error)
              } else if (snapshot != null) {
                // Sorted here rather than in the query, so no Firestore index is needed
                trySend(snapshot.documents.mapNotNull(::fromDocument).sortedWith(byName))
              }
            }
    awaitClose { registration.remove() }
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
    )
  }

  companion object {
    /** Subcollection of `/users/{uid}`. */
    const val COLLECTION = "circle"
    const val FIELD_FIRST_NAME = "firstName"
    const val FIELD_FAMILY_NAME = "familyName"
    const val FIELD_RELATIONSHIP = "relationship"
    const val FIELD_PERMISSIONS = "permissions"
    private const val TAG = "CareCircleRepository"

    private val byName =
        compareBy<CareCircleMember, String>(String.CASE_INSENSITIVE_ORDER) { it.fullName }
            .thenBy { it.uid }
  }
}
