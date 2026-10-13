// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

/**
 * Holds the [CareCircleRepository] the app uses, so ViewModels name the interface and never the
 * Firestore implementation. Tests replace [repository] with a fake.
 */
object CareCircleRepositoryProvider {
  /** Firestore by default; Firebase is only reached when the repository is first used. */
  var repository: CareCircleRepository = CareCircleRepositoryFirestore()
}
