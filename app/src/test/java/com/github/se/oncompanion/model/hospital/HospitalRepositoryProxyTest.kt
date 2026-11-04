// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HospitalRepositoryProxyTest {
  @Test
  fun realClientUsesAuthenticatedProxyAndPreservesDistinctHospitalModels() = runTest {
    val fixture = HospitalProxyTestFixture().apply { body = "[$hospital,$hospital]" }
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    assertEquals(
        HospitalSearchResult.Success(
            listOf(Hospital("way/123", "Hôpital", "Hôpital, Genève", 46.2, 6.1))
        ),
        repository.searchHospitals("  Genève  "),
    )
    val (url, headers) = fixture.requests.single()
    assertEquals("proxy.example.invalid", url.host)
    assertEquals("q=Gen%C3%A8ve&format=jsonv2&limit=10", url.query)
    assertEquals(
        mapOf("Authorization" to "Bearer id-token", "X-Firebase-AppCheck" to "app-token"),
        headers,
    )
    assertEquals(HospitalSearchResult.Success(emptyList()), repository.searchHospitals(" \n "))
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun cacheSharesTrimmedAndEmptySuccessesOfflineAndEvictsAfterFiftyQueries() = runTest {
    val fixture = HospitalProxyTestFixture()
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    for (query in 0..49) repository.searchHospitals("City $query")
    fixture.failure = IOException("offline")
    assertEquals(HospitalSearchResult.Success(emptyList()), repository.searchHospitals(" City 0 "))
    assertEquals(50, fixture.requests.size)
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.NETWORK),
        repository.searchHospitals("uncached"),
    )
    fixture.failure = null
    repository.searchHospitals("City 50")
    val before = fixture.requests.size
    repository.searchHospitals("City 1")
    assertEquals(before, fixture.requests.size)
    repository.searchHospitals("City 0")
    assertEquals(before + 1, fixture.requests.size)
  }

  @Test
  fun proxyFailuresMapToExistingRepositoryErrorsAndRemainRetryableWithoutAHint() = runTest {
    val fixture = HospitalProxyTestFixture()
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    for ((status, reason) in
        mapOf(
            400 to HospitalSearchError.SERVICE,
            401 to HospitalSearchError.SERVICE,
            403 to HospitalSearchError.SERVICE,
            404 to HospitalSearchError.SERVICE,
            429 to HospitalSearchError.RATE_LIMITED,
            500 to HospitalSearchError.SERVICE,
            503 to HospitalSearchError.SERVICE,
            302 to HospitalSearchError.SERVICE,
        )) {
      fixture.status = status
      assertEquals(
          "status $status",
          HospitalSearchResult.Error(reason),
          repository.searchHospitals("Genève"),
      )
    }
    fixture.status = 200
    fixture.body = "malformed"
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.INVALID_RESPONSE),
        repository.searchHospitals("Genève"),
    )
    fixture.failure = SocketTimeoutException("private details")
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.NETWORK),
        repository.searchHospitals("Genève"),
    )
    fixture.failure = null
    fixture.body = "[]"
    assertEquals(HospitalSearchResult.Success(emptyList()), repository.searchHospitals("Genève"))
  }

  @Test
  fun missingAuthOrAppCheckNeverReachesTheHttpTransport() = runTest {
    val fixture = HospitalProxyTestFixture()
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    fixture.signedIn = false
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.SERVICE),
        repository.searchHospitals("Genève"),
    )
    fixture.signedIn = true
    fixture.attested = false
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.SERVICE),
        repository.searchHospitals("Genève"),
    )
    assertTrue(fixture.requests.isEmpty())
  }

  @Test
  fun retryAfterBlocksEveryUncachedQueryUntilMonotonicExpiryWithoutAutomaticRequests() = runTest {
    for (status in listOf(429, 503)) {
      val fixture = HospitalProxyTestFixture()
      val time = TestTimeSource()
      val repository =
          HospitalRepositoryProxy(fixture.client(StandardTestDispatcher(testScheduler)), time)
      repository.searchHospitals("cached")
      fixture.status = status
      fixture.retryAfter = "90"
      val failure = repository.searchHospitals("Genève")
      assertTrue(failure is HospitalSearchResult.Error)
      fixture.status = 200
      fixture.retryAfter = null
      assertEquals(failure, repository.searchHospitals("different query"))
      assertEquals(HospitalSearchResult.Success(emptyList()), repository.searchHospitals("cached"))
      time += 89.seconds
      assertEquals(failure, repository.searchHospitals("another query"))
      assertEquals(2, fixture.requests.size)
      time += 1.seconds
      assertEquals(2, fixture.requests.size)
      assertEquals(
          HospitalSearchResult.Success(emptyList()),
          repository.searchHospitals("explicit retry"),
      )
      assertEquals(3, fixture.requests.size)
    }
  }

  @Test
  fun excessiveRetryAfterKeepsThisAdapterBlockedRatherThanShorteningTheWait() = runTest {
    val fixture =
        HospitalProxyTestFixture().apply {
          status = 503
          retryAfter = "9".repeat(400)
        }
    val time = TestTimeSource()
    val repository =
        HospitalRepositoryProxy(fixture.client(StandardTestDispatcher(testScheduler)), time)
    val failure = repository.searchHospitals("Genève")
    time += 365.days
    assertEquals(failure, repository.searchHospitals("Bern"))
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun concurrentIdenticalSearchesShareOneSuccessInsteadOfDuplicatingHttp() = runTest {
    val fixture = HospitalProxyTestFixture().apply { tokenGate = CompletableDeferred() }
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    val first = async { repository.searchHospitals("Genève") }
    runCurrent()
    val second = async { repository.searchHospitals("Genève") }
    runCurrent()
    fixture.tokenGate!!.complete("id-token")
    assertEquals(first.await(), second.await())
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun cancellingAnOldSearchDoesNotCacheOrSendItsResult() = runTest {
    val fixture = HospitalProxyTestFixture().apply { tokenGate = CompletableDeferred() }
    val repository =
        HospitalRepositoryProxy(
            fixture.client(StandardTestDispatcher(testScheduler)),
            TestTimeSource(),
        )
    val old = async { repository.searchHospitals("Genève") }
    runCurrent()
    old.cancelAndJoin()
    assertTrue(fixture.requests.isEmpty())
    fixture.tokenGate = null
    fixture.body = "[$hospital]"
    assertTrue(repository.searchHospitals("Genève") is HospitalSearchResult.Success)
    assertEquals(1, fixture.requests.size)
  }

  @Test
  fun rejectsDirectPublicNominatimEvenWithAlternateCaseOrTrailingDot() {
    for (host in
        listOf(
            "nominatim.openstreetmap.org",
            "NOMINATIM.OPENSTREETMAP.ORG.",
            "sub.nominatim.openstreetmap.org",
        )) {
      assertThrows(IllegalArgumentException::class.java) {
        HospitalRepositoryProxy("https://$host/search")
      }
    }
  }

  private companion object {
    const val hospital = HospitalProxyTestFixture.HOSPITAL
  }
}
