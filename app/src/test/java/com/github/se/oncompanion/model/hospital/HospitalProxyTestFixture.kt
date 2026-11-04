// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher

/** Exercises the real client and adapter with no Firebase SDK credentials or external HTTP. */
internal class HospitalProxyTestFixture : HospitalProxyTokens, HospitalProxyTransport {
  var signedIn = true
  var attested = true
  var status = 200
  var body = "[]"
  var retryAfter: String? = null
  var failure: Exception? = null
  var tokenGate: CompletableDeferred<String>? = null
  val requests = mutableListOf<Pair<URL, Map<String, String>>>()

  /** Builds the real proxy client using deterministic token and HTTP boundaries. */
  fun client(dispatcher: CoroutineDispatcher) =
      HospitalProxyClient(
          "https://proxy.example.invalid/hospitalSearch",
          this,
          this,
          dispatcher,
          Clock.fixed(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC),
      )

  override suspend fun idToken(): String? = if (signedIn) tokenGate?.await() ?: "id-token" else null

  override suspend fun appCheckToken(): String? = if (attested) "app-token" else null

  override fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse {
    requests += url to headers
    failure?.let { throw it }
    return HospitalProxyResponse(status, body, retryAfter)
  }

  companion object {
    /** Canonical backend JSON v2 result: names/coordinates stay compatible with existing models. */
    const val HOSPITAL =
        """{"osm_type":"way","osm_id":123,"category":"amenity","type":"hospital","name":"Hôpital","display_name":"Hôpital, Genève","lat":"46.2","lon":"6.1"}"""
  }
}
