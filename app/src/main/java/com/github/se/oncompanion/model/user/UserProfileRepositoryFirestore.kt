package com.github.se.oncompanion.model.user

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [UserProfileRepository] backed by Cloud Firestore, documents at `/users/{uid}`.
 *
 * Writes are applied to the local cache immediately. A write then waits up to [writeTimeoutMillis]
 * for the server: if the device is offline, it returns anyway and Firestore syncs the change when
 * the connection comes back. Errors the server reports in time (e.g. rejected by the security
 * rules) are thrown.
 */
class UserProfileRepositoryFirestore(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val writeTimeoutMillis: Long = DEFAULT_WRITE_TIMEOUT_MILLIS,
) : UserProfileRepository {

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
    write(profile.uid) {
      document(profile.uid)
          .set(toFields(profile) + (FIELD_CREATED_AT to FieldValue.serverTimestamp()))
    }
  }

  override suspend fun updateProfile(profile: UserProfile) {
    require(profile.isValid()) { "Invalid profile" }
    write(profile.uid) { document(profile.uid).set(toFields(profile), SetOptions.merge()) }
  }

  private fun document(uid: String) = db.collection(COLLECTION).document(uid)

  private suspend fun write(uid: String, start: () -> Task<Void>) {
    val task = start()
    val confirmed = withTimeoutOrNull(writeTimeoutMillis) { task.await().let { true } } ?: false
    if (!confirmed) {
      Log.i(TAG, "Profile $uid saved locally, it will sync when the device is back online")
    }
  }

  private fun toFields(profile: UserProfile): Map<String, Any?> =
      mapOf(
          FIELD_ROLE to profile.role.name,
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
    const val DEFAULT_WRITE_TIMEOUT_MILLIS = 5_000L
    private const val TAG = "UserProfileRepository"
  }
}
