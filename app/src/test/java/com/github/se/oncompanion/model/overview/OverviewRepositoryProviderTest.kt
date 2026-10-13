// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.overview

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [OverviewRepositoryProvider] exposes by default and that it can be replaced. */
class OverviewRepositoryProviderTest {
  private val original = OverviewRepositoryProvider.repository

  @After
  fun restoreProvider() {
    OverviewRepositoryProvider.repository = original
  }

  /**
   * Overview shows the stand-in until a real source exists unless told otherwise; building it needs
   * no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is FakeOverviewRepository)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeOverviewRepository()
    OverviewRepositoryProvider.repository = fake
    assertSame(fake, OverviewRepositoryProvider.repository)
  }
}
