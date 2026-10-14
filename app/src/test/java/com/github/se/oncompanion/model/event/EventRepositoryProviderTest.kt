// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [EventRepositoryProvider] exposes by default and that it can be replaced. */
class EventRepositoryProviderTest {
  private val original = EventRepositoryProvider.repository

  @After
  fun restoreProvider() {
    EventRepositoryProvider.repository = original
  }

  /** The app reads events from Firestore unless told otherwise; building it needs no Firebase. */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is EventRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeEventRepository()
    EventRepositoryProvider.repository = fake
    assertSame(fake, EventRepositoryProvider.repository)
  }
}
