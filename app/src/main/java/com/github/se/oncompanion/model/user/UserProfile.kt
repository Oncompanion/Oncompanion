package com.github.se.oncompanion.model.user

import java.time.Instant

/** What the user chose during onboarding. Only [PATIENT] is available in the app for now. */
enum class Role {
  PATIENT,
  CAREGIVER,
}

/**
 * A user's profile, stored at `/users/{uid}`. It is created at the end of onboarding, so a user
 * without a profile hasn't finished onboarding yet.
 *
 * @property uid the Firebase Auth user ID, also the document ID
 * @property role chosen during onboarding; it can't change afterwards
 * @property firstName required, see [isValid]
 * @property familyName optional
 * @property cancerType optional free text (picked from suggestions or typed by the user)
 * @property createdAt set by the server when the profile is created; `null` before that
 */
data class UserProfile(
    val uid: String,
    val role: Role,
    val firstName: String,
    val familyName: String? = null,
    val cancerType: String? = null,
    val createdAt: Instant? = null,
) {
  /** Whether the profile satisfies the same constraints as the Firestore security rules. */
  fun isValid(): Boolean =
      uid.isNotBlank() &&
          firstName.isNotBlank() &&
          firstName.length <= MAX_NAME_LENGTH &&
          (familyName == null || familyName.length <= MAX_NAME_LENGTH) &&
          (cancerType == null || cancerType.length <= MAX_CANCER_TYPE_LENGTH)

  companion object {
    /** Keep in sync with `firestore.rules`. */
    const val MAX_NAME_LENGTH = 50
    /** Keep in sync with `firestore.rules`. */
    const val MAX_CANCER_TYPE_LENGTH = 100
  }
}
