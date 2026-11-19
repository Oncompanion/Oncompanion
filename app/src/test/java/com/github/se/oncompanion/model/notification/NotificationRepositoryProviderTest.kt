// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [NotificationRepositoryProvider] exposes by default and that it can be replaced. */
class NotificationRepositoryProviderTest {
  private val original = NotificationRepositoryProvider.repository

  @After
  fun restoreProvider() {
    NotificationRepositoryProvider.repository = original
  }

  /**
   * The app reads notifications from Firestore unless told otherwise; building it needs no
   * Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is NotificationRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeNotificationRepository()
    NotificationRepositoryProvider.repository = fake
    assertSame(fake, NotificationRepositoryProvider.repository)
  }
}
