// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Startup installation must not replace a fake captured by a test or a running consumer. */
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryProviderTest {
  private val original = SettingsRepositoryProvider.repository

  @After
  fun restore() {
    SettingsRepositoryProvider.repository = original
  }

  @Test
  fun repeatedInstallationRetainsTheSelectedRepository() {
    val fake = FakeSettingsRepository()
    SettingsRepositoryProvider.repository = fake
    val context = ApplicationProvider.getApplicationContext<Context>()
    SettingsRepositoryProvider.install(context)
    SettingsRepositoryProvider.install(context)
    assertSame(fake, SettingsRepositoryProvider.repository)
  }

  /** The default provider reads and writes one device store after startup installation. */
  @Test
  fun defaultRepositoryPersistsWithoutAnAccount() = runTest {
    SettingsRepositoryProvider.install(ApplicationProvider.getApplicationContext<Context>())
    original.setAppearance(AppearancePreference.DARK)
    assertEquals(AppearancePreference.DARK, original.observeSettings().first().appearance)
    original.setAppearance(AppearancePreference.FOLLOW_DEVICE)
  }
}
