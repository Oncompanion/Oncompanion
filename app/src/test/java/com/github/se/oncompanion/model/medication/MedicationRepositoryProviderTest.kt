// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [MedicationRepositoryProvider] exposes by default and that it can be replaced. */
class MedicationRepositoryProviderTest {
  private val original = MedicationRepositoryProvider.repository

  @After
  fun restoreProvider() {
    MedicationRepositoryProvider.repository = original
  }

  /**
   * The app keeps prescriptions in Firestore unless told otherwise; building it needs no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is MedicationRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeMedicationRepository()
    MedicationRepositoryProvider.repository = fake
    assertSame(fake, MedicationRepositoryProvider.repository)
  }
}
