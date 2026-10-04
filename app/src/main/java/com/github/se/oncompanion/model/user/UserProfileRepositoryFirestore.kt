package com.github.se.oncompanion.model.user

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * [UserProfileRepository] backed by Cloud Firestore, documents at `/users/{uid}`.
 *
 * Writes return as soon as they are applied to the local cache, without waiting for the server, so
 * they never block the UI (offline, Firestore syncs them when the connection comes back). If the
 * server later rejects a write (security rules), Firestore rolls the local change back and the
 * rejection is logged; [UserProfile.isValid] mirrors the rules so this shouldn't happen.
 */
class UserProfileRepositoryFirestore(
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
) : UserProfileRepository {

  /** Uses the given [FirebaseFirestore] instance, e.g. one connected to the emulator. */
  constructor(db: FirebaseFirestore) : this({ db })

  // Firestore is only accessed when first used, so screens can create this repository (e.g. as a
  // ViewModel default) where Firebase isn't initialized, like unit tests
  private val db: FirebaseFirestore by lazy(dbProvider)

  override suspend fun getProfile(uid: String): UserProfile? =
      fromDocument(document(uid).get().await())

  override fun observeProfile(uid: String): Flow<UserProfile?> = callbackFlow {
    val registration =
        document(uid).addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
          } else {
            trySend(snapshot?.let(::fromDocument))
          }
        }
    awaitClose { registration.remove() }
  }

  override suspend fun createProfile(profile: UserProfile) {
    require(profile.isValid()) { "Invalid profile" }
    document(profile.uid)
        .set(
            editableFields(profile) +
                mapOf(
                    FIELD_ROLE to profile.role.name,
                    FIELD_CREATED_AT to FieldValue.serverTimestamp(),
                )
        )
        .logServerRejection(profile.uid)
  }

  override suspend fun updateProfile(profile: UserProfile) {
    require(profile.isValid()) { "Invalid profile" }
    // update() (not a merge set) so a missing profile is never created half-filled
    document(profile.uid).update(editableFields(profile)).logServerRejection(profile.uid)
  }

  private fun document(uid: String) = db.collection(COLLECTION).document(uid)

  private fun Task<Void>.logServerRejection(uid: String) {
    addOnFailureListener { e ->
      Log.e(TAG, "The server rejected the profile $uid; the local change was rolled back", e)
    }
  }

  /** The fields a user can change after onboarding (not the role nor createdAt). */
  private fun editableFields(profile: UserProfile): Map<String, Any?> =
      mapOf(
          FIELD_FIRST_NAME to profile.firstName,
          FIELD_FAMILY_NAME to profile.familyName,
          FIELD_CANCER_TYPE to profile.cancerType,
      )

  private fun fromDocument(doc: DocumentSnapshot): UserProfile? {
    if (!doc.exists()) return null
    return try {
      UserProfile(
          uid = doc.id,
          role = Role.valueOf(doc.getString(FIELD_ROLE)!!),
          firstName = doc.getString(FIELD_FIRST_NAME)!!,
          familyName = doc.getString(FIELD_FAMILY_NAME),
          cancerType = doc.getString(FIELD_CANCER_TYPE),
          // Pending server timestamps read as null until the write reaches the server
          createdAt = doc.getTimestamp(FIELD_CREATED_AT)?.let(Timestamp::toInstant),
      )
    } catch (e: Exception) {
      Log.e(TAG, "Malformed profile document ${doc.id}", e)
      null
    }
  }

  companion object {
    const val COLLECTION = "users"
    const val FIELD_ROLE = "role"
    const val FIELD_FIRST_NAME = "firstName"
    const val FIELD_FAMILY_NAME = "familyName"
    const val FIELD_CANCER_TYPE = "cancerType"
    const val FIELD_CREATED_AT = "createdAt"
    private const val TAG = "UserProfileRepository"
  }
}
