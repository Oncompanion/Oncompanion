package com.github.se.oncompanion.model.hospital

import java.io.IOException
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HospitalRepositoryNominatimTest {
  private val transport = RecordingTransport()

  @Test
  fun readsHospitalsAndEncodesOnlyTheSubmittedPublicSearch() = runTest {
    transport.body = "[$hospital, $hospital]"
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    val result = repo.searchHospitals("  Hôpital & Genève  ") as HospitalSearchResult.Success
    assertEquals(
        listOf(Hospital("way/123", "Hôpital", "Hôpital, Genève, Suisse", 46.2, 6.1)),
        result.hospitals,
    )
    val url = transport.urls.single()
    assertEquals("https", url.protocol)
    assertEquals("example.invalid", url.host)
    assertTrue(URLDecoder.decode(url.query, "UTF-8").contains("q=Hôpital & Genève [hospital]"))
    assertTrue(url.query.contains("format=jsonv2&limit=10"))
  }

  @Test
  fun emptyAndBlankSearchesSucceedAndBlankNeverUsesHttp() = runTest {
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    assertEquals(HospitalSearchResult.Success(emptyList()), repo.searchHospitals(" \n "))
    assertTrue(transport.urls.isEmpty())
    assertEquals(HospitalSearchResult.Success(emptyList()), repo.searchHospitals("Unknown"))
    assertEquals(1, transport.urls.size)
  }

  @Test
  fun excludesNonHospitalsAndUsesAddressWhenNameIsMissing() = runTest {
    val unnamed = hospital.replace("\"name\":\"Hôpital\",", "")
    val other = hospital.replace("\"hospital\"", "\"clinic\"")
    transport.body = "[$other, $unnamed]"
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    val result = repo.searchHospitals("Genève") as HospitalSearchResult.Success
    assertEquals(1, result.hospitals.size)
    assertEquals("Hôpital", result.hospitals.single().name)
  }

  @Test
  fun invalidJsonFieldsAndCoordinatesReturnExplicitErrors() = runTest {
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    val invalid =
        listOf(
            "not json",
            "{}",
            "[null]",
            "[{\"type\":\"hospital\"}]",
            "[${hospital.replace("46.2", "NaN")}]",
            "[${hospital.replace("6.1", "181")}]",
            "[${hospital.replace("way", "bad")}]",
            "[${hospital.replace("123", "0")}]",
            "[${hospital.replace("46.2", "oops")}]",
            "[${hospital.replace("Hôpital, Genève, Suisse", "")}]",
        )
    invalid.forEachIndexed { index, body ->
      transport.body = body
      assertEquals(
          HospitalSearchResult.Error(HospitalSearchError.INVALID_RESPONSE),
          repo.searchHospitals("query $index"),
      )
    }
  }

  @Test
  fun serviceAndNetworkFailuresAreNotCachedAndCanBeRetried() = runTest {
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    transport.status = 503
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.SERVICE),
        repo.searchHospitals("Geneva"),
    )
    transport.status = 429
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.RATE_LIMITED),
        repo.searchHospitals("Geneva"),
    )
    transport.error = IOException("Offline")
    assertEquals(
        HospitalSearchResult.Error(HospitalSearchError.NETWORK),
        repo.searchHospitals("Geneva"),
    )
    transport.error = null
    transport.status = 200
    assertEquals(HospitalSearchResult.Success(emptyList()), repo.searchHospitals("Geneva"))
    assertEquals(4, transport.urls.size)
  }

  @Test
  fun cachesSuccessfulAndEmptyQueriesIncludingConcurrentDuplicates() = runTest {
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    val first = async { repo.searchHospitals("Geneva") }
    val second = async { repo.searchHospitals(" Geneva ") }
    assertEquals(first.await(), second.await())
    assertEquals(1, transport.urls.size)
    transport.body = "[$hospital]"
    val loaded = repo.searchHospitals("Lausanne")
    assertEquals(loaded, repo.searchHospitals("Lausanne"))
    assertEquals(2, transport.urls.size)
  }

  @Test
  fun differentInstancesShareTheRateGateAndCancelledWaitDoesNotRequest() = runTest {
    val gate = HospitalRequestGate()
    val dispatcher = StandardTestDispatcher(testScheduler)
    val first = HospitalRepositoryNominatim(endpoint, transport, dispatcher, gate)
    val second = HospitalRepositoryNominatim(endpoint, transport, dispatcher, gate)
    first.searchHospitals("Geneva")
    val waiting = async { second.searchHospitals("Lausanne") }
    runCurrent()
    advanceTimeBy(999)
    runCurrent()
    assertEquals(1, transport.urls.size)
    advanceTimeBy(1)
    runCurrent()
    waiting.await()
    assertEquals(2, transport.urls.size)
    val cancelled = async { first.searchHospitals("Bern") }
    runCurrent()
    cancelled.cancel()
    runCurrent()
    assertEquals(2, transport.urls.size)
  }

  @Test
  fun transportCancellationPropagates() = runTest {
    transport.error = CancellationException("Cancelled")
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    try {
      repo.searchHospitals("Geneva")
      throw AssertionError("Cancellation must propagate")
    } catch (_: CancellationException) {
      assertEquals(1, transport.urls.size)
    }
  }

  @Test
  fun cacheIsBoundedAndEvictsOldestQuery() = runTest {
    val repo =
        HospitalRepositoryNominatim(
            endpoint,
            transport,
            StandardTestDispatcher(testScheduler),
            HospitalRequestGate(),
        )
    repeat(51) { repo.searchHospitals("city $it") }
    repo.searchHospitals("city 0")
    assertEquals(52, transport.urls.size)
  }

  @Test
  fun rejectsInsecureOrAmbiguousEndpointConfiguration() {
    listOf("http://example.invalid/search", "$endpoint?key=value", "$endpoint#fragment").forEach {
      try {
        HospitalRepositoryNominatim(it)
        throw AssertionError("Invalid endpoint accepted")
      } catch (_: IllegalArgumentException) {
        // No network access occurs during construction.
      }
    }
  }

  private class RecordingTransport : HospitalHttpTransport {
    val urls = mutableListOf<URL>()
    var body = "[]"
    var status = 200
    var error: Exception? = null

    override fun get(url: URL): HospitalHttpResponse {
      urls.add(url)
      error?.let { throw it }
      return HospitalHttpResponse(status, body)
    }
  }

  private companion object {
    const val endpoint = "https://example.invalid/search"
    const val hospital =
        """{"osm_type":"way","osm_id":123,"category":"amenity","type":"hospital","name":"Hôpital","display_name":"Hôpital, Genève, Suisse","lat":"46.2","lon":"6.1"}"""
  }
}
