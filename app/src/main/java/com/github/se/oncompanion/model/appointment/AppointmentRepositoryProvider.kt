// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.appointment

/**
 * Holds the [AppointmentRepository] the app uses, so ViewModels name the interface and never the
 * Firestore implementation. Tests replace [repository] with a fake.
 */
object AppointmentRepositoryProvider {
  /** Firestore by default; Firebase is only reached when the repository is first used. */
  @Volatile var repository: AppointmentRepository = AppointmentRepositoryFirestore()
}
