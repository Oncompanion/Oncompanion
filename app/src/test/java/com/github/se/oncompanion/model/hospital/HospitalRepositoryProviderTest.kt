// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.hospital

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * Checks provisioning without a public endpoint, stable configured instances and fake overrides.
 */
@RunWith(RobolectricTestRunner::class)
class HospitalRepositoryProviderTest {
  private val original = HospitalRepositoryProvider.repository

  /** Restores the app's repository so provider overrides cannot affect other tests. */
  @After
  fun restoreProvider() {
    HospitalRepositoryProvider.repository = original
  }

  /** An unconfigured provider supports blank input and reports unavailable search without HTTP. */
  @Test
  fun defaultDoesNotRequireAnEndpointOrNetwork() = runTest {
    HospitalRepositoryProvider.disable()
    assertFalse(HospitalRepositoryProvider.isConfigured)
    val original = HospitalRepositoryProvider.repository
    assertEquals(HospitalSearchResult.Success(emptyList()), original.searchHospitals("  "))
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.SERVICE),
        original.searchHospitals("Geneva"),
    )
  }

  /** Consumers receive the exact fake installed by a test. */
  @Test
  fun exposesTheRepositoryItWasGiven() {
    val fake = FakeHospitalRepository()
    HospitalRepositoryProvider.repository = fake
    assertSame(fake, HospitalRepositoryProvider.repository)
  }

  /** Missing deployment configuration leaves manual mode and test overrides intact. */
  @Test
  fun blankStartupConfigurationLeavesTheRepositoryUnchanged() {
    HospitalRepositoryProvider.disable()
    val unconfigured = HospitalRepositoryProvider.repository
    for (endpoint in listOf("", "  ")) {
      HospitalRepositoryProvider.configureAtStartup(endpoint)
      assertSame(unconfigured, HospitalRepositoryProvider.repository)
      assertFalse(HospitalRepositoryProvider.isConfigured)
    }
    val fake = FakeHospitalRepository()
    HospitalRepositoryProvider.repository = fake
    HospitalRepositoryProvider.configureAtStartup("")
    assertSame(fake, HospitalRepositoryProvider.repository)
  }

  /** Activity recreation preserves the adapter's public-result cache and retry gate. */
  @Test
  fun repeatedStartupConfigurationKeepsTheSharedAdapter() {
    HospitalRepositoryProvider.disable()
    HospitalRepositoryProvider.configureAtStartup("https://example.invalid/search")
    val configured = HospitalRepositoryProvider.repository
    assertTrue(HospitalRepositoryProvider.isConfigured)
    HospitalRepositoryProvider.configureAtStartup("https://example.invalid/search")
    HospitalRepositoryProvider.configureAtStartup("")
    assertSame(configured, HospitalRepositoryProvider.repository)
  }

  /**
   * Startup uses the existing endpoint validation rather than permitting direct upstream access.
   */
  @Test
  fun invalidStartupEndpointDoesNotEnableSearch() {
    for (endpoint in
        listOf("http://example.invalid/search", "https://nominatim.openstreetmap.org/search")) {
      HospitalRepositoryProvider.disable()
      val unconfigured = HospitalRepositoryProvider.repository
      ShadowLog.clear()
      HospitalRepositoryProvider.configureAtStartup(endpoint)
      assertSame(unconfigured, HospitalRepositoryProvider.repository)
      assertFalse(HospitalRepositoryProvider.isConfigured)
      val log = ShadowLog.getLogsForTag("HospitalRepositoryProvider").single()
      assertEquals("Invalid hospital proxy configuration: IllegalArgumentException", log.msg)
      assertNull(log.throwable)
    }
  }

  /** Startup must not replace a fake with real credentials and networking when a URL is set. */
  @Test
  fun nonBlankStartupConfigurationPreservesTestOverrides() {
    val fake = FakeHospitalRepository()
    HospitalRepositoryProvider.repository = fake
    HospitalRepositoryProvider.configureAtStartup("https://example.invalid/search")
    assertSame(fake, HospitalRepositoryProvider.repository)
    assertFalse(HospitalRepositoryProvider.isConfigured)
  }

  /** Configuration constructs the HTTP implementation once, without issuing a request. */
  @Test
  fun configuredRepositoryIsSharedAcrossConsumers() = runTest {
    HospitalRepositoryProvider.configure("https://example.invalid/search")
    val configured = HospitalRepositoryProvider.repository
    assertTrue(configured is HospitalRepositoryProxy)
    assertTrue(HospitalRepositoryProvider.isConfigured)
    assertSame(configured, HospitalRepositoryProvider.repository)
    assertEquals(HospitalSearchResult.Success(emptyList()), configured.searchHospitals(""))
  }

  /** Invalid configuration does not discard a working repository or its cache. */
  @Test
  fun invalidEndpointLeavesTheRepositoryUnchanged() {
    val fake = FakeHospitalRepository()
    HospitalRepositoryProvider.repository = fake
    listOf(
            "http://example.invalid/search",
            "https://example.invalid/search?q=test",
            "https://nominatim.openstreetmap.org/search",
        )
        .forEach {
          assertThrows(IllegalArgumentException::class.java) {
            HospitalRepositoryProvider.configure(it)
          }
          assertSame(fake, HospitalRepositoryProvider.repository)
        }
  }
}
