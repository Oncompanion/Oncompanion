// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [CareCircleRepositoryProvider] exposes by default and that it can be replaced. */
class CareCircleRepositoryProviderTest {
  private val original = CareCircleRepositoryProvider.repository

  @After
  fun restoreProvider() {
    CareCircleRepositoryProvider.repository = original
  }

  /**
   * The app keeps care circles in Firestore unless told otherwise; building it needs no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is CareCircleRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeCareCircleRepository()
    CareCircleRepositoryProvider.repository = fake
    assertSame(fake, CareCircleRepositoryProvider.repository)
  }
}
