// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [SymptomRepositoryProvider] exposes by default and that it can be replaced. */
class SymptomRepositoryProviderTest {
  private val original = SymptomRepositoryProvider.repository

  @After
  fun restoreProvider() {
    SymptomRepositoryProvider.repository = original
  }

  /** The app keeps symptoms in Firestore unless told otherwise; building it needs no Firebase. */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is SymptomRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeSymptomRepository()
    SymptomRepositoryProvider.repository = fake
    assertSame(fake, SymptomRepositoryProvider.repository)
  }
}
