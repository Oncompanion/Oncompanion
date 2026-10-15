// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.model.hospital

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Checks provisioning without a public endpoint, stable configured instances and fake overrides.
 */
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
    assertSame(original, HospitalRepositoryProvider.repository)
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

  /** Configuration constructs the HTTP implementation once, without issuing a request. */
  @Test
  fun configuredRepositoryIsSharedAcrossConsumers() = runTest {
    HospitalRepositoryProvider.configure("https://example.invalid/search")
    val configured = HospitalRepositoryProvider.repository
    assertTrue(configured is HospitalRepositoryNominatim)
    assertSame(configured, HospitalRepositoryProvider.repository)
    assertEquals(HospitalSearchResult.Success(emptyList()), configured.searchHospitals(""))
  }

  /** Invalid configuration does not discard a working repository or its cache. */
  @Test
  fun invalidEndpointLeavesTheRepositoryUnchanged() {
    val fake = FakeHospitalRepository()
    HospitalRepositoryProvider.repository = fake
    listOf("http://example.invalid/search", "https://example.invalid/search?q=test").forEach {
      assertThrows(IllegalArgumentException::class.java) {
        HospitalRepositoryProvider.configure(it)
      }
      assertSame(fake, HospitalRepositoryProvider.repository)
    }
  }
}
