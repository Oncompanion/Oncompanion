// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.user

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks what [UserProfileRepositoryProvider] exposes by default and that it can be replaced. */
class UserProfileRepositoryProviderTest {
  private val original = UserProfileRepositoryProvider.repository

  @After
  fun restoreProvider() {
    UserProfileRepositoryProvider.repository = original
  }

  /** The app keeps profiles in Firestore unless told otherwise; building it needs no Firebase. */
  @Test
  fun exposesTheAppRepositoryByDefault() {
    assertTrue(original is UserProfileRepositoryFirestore)
  }

  /** The repository given to the provider is the one it hands out afterwards. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeUserProfileRepository()
    UserProfileRepositoryProvider.repository = fake
    assertSame(fake, UserProfileRepositoryProvider.repository)
  }
}
