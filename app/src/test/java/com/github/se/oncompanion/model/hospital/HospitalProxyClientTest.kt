// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.github.se.oncompanion.model.session.SessionAccess
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
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
class HospitalProxyClientTest {
  private val tokens = FakeProxyTokens()
  private val transport = RecordingProxyTransport()
  private val clock = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC)
  private val endpoint = "https://proxy.example.invalid/hospitalSearch"

  @Test
  fun constructsTheActualGetContractWithFreshTokensForEachExplicitCall() = runTest {
    transport.response = HospitalProxyResponse(200, "[$hospital]")
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    val result = client.search("  Hôpital de l'Île & Genève / HUG  ")
    assertEquals(
        HospitalProxyResult.Success(
            listOf(Hospital("way/123", "Hôpital", "Hôpital, Genève", 46.2, 6.1))
        ),
        result,
    )
    val (url, headers) = transport.requests.single()
    assertEquals(endpoint, url.toExternalForm().substringBefore('?'))
    assertEquals(
        "q=Hôpital de l'Île & Genève / HUG&format=jsonv2&limit=10",
        URLDecoder.decode(url.query, "UTF-8"),
    )
    assertEquals(
        mapOf("Authorization" to "Bearer id-token", "X-Firebase-AppCheck" to "app-token"),
        headers,
    )
    tokens.id = "refreshed-id"
    tokens.app = "refreshed-app"
    client.search("Bern")
    assertEquals(2, tokens.authCalls)
    assertEquals(2, tokens.appCalls)
    assertEquals("Bearer refreshed-id", transport.requests.last().second["Authorization"])
    assertEquals("refreshed-app", transport.requests.last().second["X-Firebase-AppCheck"])
  }

  @Test
  fun queryParametersCannotEscapeTheEncodedQueryAndEndpointIsConfigurable() = runTest {
    val other = "https://staging.example.invalid:8443/functions/hospitalSearch"
    val client =
        HospitalProxyClient(other, tokens, transport, StandardTestDispatcher(testScheduler), clock)
    client.search("Genève&limit=100#fragment")
    val url = transport.requests.single().first
    assertEquals("staging.example.invalid", url.host)
    assertEquals(8443, url.port)
    assertNull(url.ref)
    assertEquals(3, url.query.split('&').size)
    assertEquals(
        "Genève&limit=100#fragment",
        URLDecoder.decode(url.query.substringAfter("q=").substringBefore('&'), "UTF-8"),
    )
  }

  @Test
  fun requiresTrustedHttpsConfigurationAndConstructsWithoutInitializingFirebase() {
    for (bad in
        listOf(
            "http://proxy.invalid/search",
            "https://proxy.invalid/search?q=x",
            "https://proxy.invalid/#frag",
            "https://user:password@proxy.invalid/search",
            "https:///search",
        )) {
      assertThrows(bad, IllegalArgumentException::class.java) { HospitalProxyClient(bad) }
    }
    FirebaseHospitalProxyTokens()
    HospitalProxyClient(endpoint)
  }

  @Test
  fun blankAndOversizedQueriesDoNotFetchTokensOrSendRequests() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    assertEquals(HospitalProxyResult.Success(emptyList()), client.search(" \n "))
    assertEquals(
        HospitalProxyResult.Failure(HospitalProxyError.INVALID_REQUEST),
        client.search("x".repeat(201)),
    )
    assertEquals(0, tokens.authCalls)
    assertEquals(0, tokens.appCalls)
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun missingInvalidAndFailedTokensStopBeforeNetworking() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    for (invalid in listOf(null, "", " ", "header\r\ninjection", "x".repeat(8193))) {
      tokens.id = invalid
      assertEquals(
          "id: $invalid",
          HospitalProxyResult.Failure(HospitalProxyError.AUTHENTICATION),
          client.search("Genève"),
      )
      tokens.id = "id-token"
      tokens.app = invalid
      assertEquals(
          "app token",
          HospitalProxyResult.Failure(HospitalProxyError.APP_CHECK),
          client.search("Genève"),
      )
      tokens.app = "app-token"
    }
    tokens.authFailure = IOException("sensitive exception")
    assertEquals(
        HospitalProxyResult.Failure(HospitalProxyError.AUTHENTICATION),
        client.search("Genève"),
    )
    tokens.authFailure = null
    tokens.appFailure = IllegalStateException("sensitive credential")
    assertEquals(HospitalProxyResult.Failure(HospitalProxyError.APP_CHECK), client.search("Genève"))
    assertTrue(transport.requests.isEmpty())
  }

  @Test
  fun missingAuthDoesNotRequestAppCheckAndCancellationsPropagate() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    tokens.id = null
    client.search("Genève")
    assertEquals(0, tokens.appCalls)
    tokens.id = "id-token"
    for (phase in listOf("auth", "app", "transport")) {
      tokens.authFailure = if (phase == "auth") CancellationException() else null
      tokens.appFailure = if (phase == "app") CancellationException() else null
      transport.failure = if (phase == "transport") CancellationException() else null
      try {
        client.search("Genève")
        fail("Cancellation was swallowed: $phase")
      } catch (_: CancellationException) {
        /* Lifecycle cancellation is not a typed service error. */
      }
    }
  }

  @Test
  fun successfulEmptyArraysAndEveryBackendHospitalIdentityAreSupported() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    assertEquals(HospitalProxyResult.Success(emptyList()), client.search("Unknown"))
    for (type in listOf("node", "way", "relation")) {
      transport.response =
          HospitalProxyResponse(
              200,
              "[${hospital.replace("way", type).replace("amenity", "healthcare")}]",
          )
      assertEquals(
          "$type/123",
          (client.search("Genève") as HospitalProxyResult.Success).hospitals.single().id,
      )
    }
  }

  @Test
  fun malformedOrOutOfContractResponsesFailWithoutLeakingBodies() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    val invalid =
        listOf(
            "",
            "not-json",
            "{}",
            "null",
            "[null]",
            "[] trailing",
            "[{}]",
            "[${List(11) { hospital }.joinToString()}]",
        ) +
            listOf(
                    "\"osm_id\":123" to "\"osm_id\":\"123\"",
                    "\"osm_id\":123" to "\"osm_id\":1.5",
                    "\"osm_id\":123" to "\"osm_id\":0",
                    "\"osm_id\":123" to "\"osm_id\":9007199254740992",
                    "\"way\"" to "\"invalid\"",
                    "\"hospital\"" to "\"clinic\"",
                    "\"amenity\"" to "\"shop\"",
                    "\"name\":\"Hôpital\"" to "\"name\":123",
                    "\"name\":\"Hôpital\"" to "\"name\":\"\"",
                    "\"display_name\":\"Hôpital, Genève\"" to "\"display_name\":\" \"",
                    "\"46.2\"" to "\"NaN\"",
                    "\"46.2\"" to "\"Infinity\"",
                    "\"46.2\"" to "\"91\"",
                    "\"6.1\"" to "\"-181\"",
                    "\"46.2\"" to "46.2",
                    "\"6.1\"" to "null",
                )
                .map { (old, new) -> "[${hospital.replace(old, new)}]" }
    for (body in invalid) {
      transport.response = HospitalProxyResponse(200, body)
      assertEquals(
          body,
          HospitalProxyResult.Failure(HospitalProxyError.INVALID_RESPONSE),
          client.search("Genève"),
      )
    }
  }

  @Test
  fun centralStatusMappingPreservesUsefulDistinctionsAndNeverRetries() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    val cases =
        mapOf(
            400 to HospitalProxyError.INVALID_REQUEST,
            401 to HospitalProxyError.AUTHENTICATION,
            403 to HospitalProxyError.APP_CHECK,
            404 to HospitalProxyError.ENDPOINT,
            405 to HospitalProxyError.INVALID_REQUEST,
            413 to HospitalProxyError.INVALID_REQUEST,
            418 to HospitalProxyError.INVALID_REQUEST,
            429 to HospitalProxyError.RATE_LIMITED,
            500 to HospitalProxyError.SERVER,
            502 to HospitalProxyError.SERVER,
            503 to HospitalProxyError.UNAVAILABLE,
            504 to HospitalProxyError.SERVER,
            204 to HospitalProxyError.UNEXPECTED_STATUS,
            302 to HospitalProxyError.UNEXPECTED_STATUS,
            421 to HospitalProxyError.INVALID_REQUEST,
            600 to HospitalProxyError.UNEXPECTED_STATUS,
        )
    for ((status, kind) in cases) {
      transport.response = HospitalProxyResponse(status, "sensitive untrusted body", "90")
      val retry =
          if (status == 429 || status == 503) HospitalProxyRetryAfter.Delay(Duration.ofSeconds(90))
          else null
      assertEquals(
          "status $status",
          HospitalProxyResult.Failure(kind, status, retry),
          client.search("Genève"),
      )
    }
    assertEquals(cases.size, transport.requests.size)
  }

  @Test
  fun networkFailuresAndTimeoutsAreDistinctAndDoNotRetry() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    for ((exception, kind) in
        listOf(
            IOException("private") to HospitalProxyError.NETWORK,
            SocketTimeoutException("private") to HospitalProxyError.TIMEOUT,
            IllegalArgumentException("oversized response") to HospitalProxyError.INVALID_RESPONSE,
        )) {
      transport.failure = exception
      assertEquals(HospitalProxyResult.Failure(kind), client.search("Genève"))
    }
    assertEquals(3, transport.requests.size)
  }

  @Test
  fun retryHintsSupportSecondsDatesAndUnrepresentableLongWaits() {
    val now = Clock.fixed(Instant.parse("2026-11-02T12:00:00.500Z"), ZoneOffset.UTC)
    for (missing in
        listOf(
            null,
            "",
            " ",
            "bad",
            "-1",
            "0",
            "1.5",
            "+90",
            "Mon, 2 Nov 2026 11:59:00 GMT",
        )) assertNull(missing, parseRetryAfter(missing, now))
    for ((value, seconds) in
        mapOf(
            "1" to 1L,
            " 90 " to 90L,
            "180" to 180L,
            "86400" to 86400L,
            "Mon, 2 Nov 2026 12:01:00 GMT" to 60L,
        )) assertEquals(
        value,
        HospitalProxyRetryAfter.Delay(Duration.ofSeconds(seconds)),
        parseRetryAfter(value, now),
    )
    for (value in
        listOf(
            "86401",
            Long.MAX_VALUE.toString(),
            "9".repeat(400),
            "Tue, 3 Nov 2026 12:00:02 GMT",
        )) assertEquals(value, HospitalProxyRetryAfter.BeyondLimit, parseRetryAfter(value, now))
  }

  @Test
  fun serverHintsDoNotGainAClientSideRetryLoop() = runTest {
    val client =
        HospitalProxyClient(
            endpoint,
            tokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    transport.response = HospitalProxyResponse(503, "", "9".repeat(400))
    assertEquals(
        HospitalProxyResult.Failure(
            HospitalProxyError.UNAVAILABLE,
            503,
            HospitalProxyRetryAfter.BeyondLimit,
        ),
        client.search("Genève"),
    )
    transport.response = HospitalProxyResponse(429, "", "Mon, 2 Nov 2026 12:01:30 GMT")
    assertEquals(
        HospitalProxyResult.Failure(
            HospitalProxyError.RATE_LIMITED,
            429,
            HospitalProxyRetryAfter.Delay(Duration.ofSeconds(90)),
        ),
        client.search("Genève"),
    )
    assertEquals(2, transport.requests.size)
  }

  @Test
  fun sessionFreezeCancelsTokenWaitBeforeAnOldCredentialCanBeSent() = runTest {
    val pendingTokens =
        object : HospitalProxyTokens {
          override suspend fun idToken(): String {
            awaitCancellation()
          }

          override suspend fun appCheckToken(): String = "unused"
        }
    val client =
        HospitalProxyClient(
            endpoint,
            pendingTokens,
            transport,
            StandardTestDispatcher(testScheduler),
            clock,
        )
    val pending = async { client.search("Genève") }
    runCurrent()
    try {
      SessionAccess.freeze()
      assertTrue(pending.isCancelled)
      assertTrue(transport.requests.isEmpty())
    } finally {
      SessionAccess.resume()
    }
  }

  private class FakeProxyTokens : HospitalProxyTokens {
    var id: String? = "id-token"
    var app: String? = "app-token"
    var authFailure: Exception? = null
    var appFailure: Exception? = null
    var authCalls = 0
    var appCalls = 0

    override suspend fun idToken(): String? {
      authCalls++
      authFailure?.let { throw it }
      return id
    }

    override suspend fun appCheckToken(): String? {
      appCalls++
      appFailure?.let { throw it }
      return app
    }
  }

  private class RecordingProxyTransport : HospitalProxyTransport {
    val requests = mutableListOf<Pair<URL, Map<String, String>>>()
    var response = HospitalProxyResponse(200, "[]")
    var failure: Exception? = null

    override fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse {
      requests += url to headers
      failure?.let { throw it }
      return response
    }
  }

  private companion object {
    const val hospital =
        """{"osm_type":"way","osm_id":123,"category":"amenity","type":"hospital","name":"Hôpital","display_name":"Hôpital, Genève","lat":"46.2","lon":"6.1"}"""
  }
}
