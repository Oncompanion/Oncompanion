// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.appointment

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [AppointmentRepositoryProvider] exposes by default and that it can be replaced. */
class AppointmentRepositoryProviderTest {
  private val original = AppointmentRepositoryProvider.repository

  @After
  fun restoreProvider() {
    AppointmentRepositoryProvider.repository = original
  }

  /**
   * The app keeps appointments in Firestore unless told otherwise; building it needs no Firebase.
   */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is AppointmentRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeAppointmentRepository()
    AppointmentRepositoryProvider.repository = fake
    assertSame(fake, AppointmentRepositoryProvider.repository)
  }
}
