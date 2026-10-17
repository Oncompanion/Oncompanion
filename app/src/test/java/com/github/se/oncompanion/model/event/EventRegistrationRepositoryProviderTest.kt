// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what [EventRegistrationRepositoryProvider] exposes by default and that it can be replaced.
 */
class EventRegistrationRepositoryProviderTest {
  private val original = EventRegistrationRepositoryProvider.repository

  @After
  fun restoreProvider() {
    EventRegistrationRepositoryProvider.repository = original
  }

  /**
   * The app stores confirmations in Firestore unless told otherwise; building it needs no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is EventRegistrationRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeEventRegistrationRepository()
    EventRegistrationRepositoryProvider.repository = fake
    assertSame(fake, EventRegistrationRepositoryProvider.repository)
  }
}
