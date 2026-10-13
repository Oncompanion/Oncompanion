// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.overview

/**
 * Holds the [OverviewRepository] the app uses, so ViewModels name the interface and never an
 * implementation. Tests replace [repository] with their own.
 */
object OverviewRepositoryProvider {
  /** An empty stand-in until an implementation reads the planning data. */
  var repository: OverviewRepository = FakeOverviewRepository()
}
