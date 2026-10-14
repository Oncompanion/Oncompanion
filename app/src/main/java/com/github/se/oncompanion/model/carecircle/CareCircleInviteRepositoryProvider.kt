// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

/**
 * Holds the [CareCircleInviteRepository] the app uses, so ViewModels name the interface and never
 * the Firestore implementation. Tests replace [repository] with a fake.
 */
object CareCircleInviteRepositoryProvider {
  /** Firestore by default; Firebase is only reached when the repository is first used. */
  @Volatile var repository: CareCircleInviteRepository = CareCircleInviteRepositoryFirestore()
}
