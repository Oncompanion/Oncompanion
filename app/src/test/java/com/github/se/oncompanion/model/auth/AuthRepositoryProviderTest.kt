// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.auth

import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [AuthRepositoryProvider] exposes by default and that it can be replaced. */
class AuthRepositoryProviderTest {
  private val original = AuthRepositoryProvider.repository

  @After
  fun restoreProvider() {
    AuthRepositoryProvider.repository = original
  }

  /** The app signs in with Firebase unless told otherwise; building it needs no Firebase. */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is AuthRepositoryFirebase)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeAuthRepository()
    AuthRepositoryProvider.repository = fake
    assertSame(fake, AuthRepositoryProvider.repository)
  }
}
