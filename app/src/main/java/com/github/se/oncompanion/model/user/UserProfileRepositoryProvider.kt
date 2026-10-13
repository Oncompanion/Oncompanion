// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.user

/**
 * Holds the [UserProfileRepository] the app uses, so ViewModels name the interface and never the
 * Firestore implementation. Tests replace [repository] with a fake.
 */
object UserProfileRepositoryProvider {
  /** Firestore by default; Firebase is only reached when the repository is first used. */
  @Volatile var repository: UserProfileRepository = UserProfileRepositoryFirestore()
}
