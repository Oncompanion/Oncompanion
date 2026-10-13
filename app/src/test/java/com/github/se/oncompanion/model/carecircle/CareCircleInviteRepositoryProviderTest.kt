// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what [CareCircleInviteRepositoryProvider] exposes by default and that it can be replaced.
 */
class CareCircleInviteRepositoryProviderTest {
  private val original = CareCircleInviteRepositoryProvider.repository

  @After
  fun restoreProvider() {
    CareCircleInviteRepositoryProvider.repository = original
  }

  /** The app keeps invites in Firestore unless told otherwise; building it needs no Firebase. */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is CareCircleInviteRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeCareCircleInviteRepository()
    CareCircleInviteRepositoryProvider.repository = fake
    assertSame(fake, CareCircleInviteRepositoryProvider.repository)
  }
}
