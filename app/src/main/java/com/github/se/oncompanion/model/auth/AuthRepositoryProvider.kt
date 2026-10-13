// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.auth

/**
 * Holds the [AuthRepository] the app uses, so ViewModels name the interface and never the Firebase
 * implementation. Tests replace [repository] with a fake.
 */
object AuthRepositoryProvider {
  /** Firebase Authentication by default; Firebase is only reached when it is first used. */
  var repository: AuthRepository = AuthRepositoryFirebase()
}
