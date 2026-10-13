// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

/**
 * Holds the [MedicationRepository] the app uses, so ViewModels name the interface and never the
 * Firestore implementation. Tests replace [repository] with a fake.
 */
object MedicationRepositoryProvider {
  /** Firestore by default; Firebase is only reached when the repository is first used. */
  var repository: MedicationRepository = MedicationRepositoryFirestore()
}
