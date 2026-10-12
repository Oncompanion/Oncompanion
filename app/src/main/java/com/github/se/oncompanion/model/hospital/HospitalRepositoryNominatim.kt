package com.github.se.oncompanion.model.hospital

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

/**
 * Explicit hospital searches over Nominatim's JSON v2 API, using platform HTTP and JSON support.
 *
 * [searchEndpoint] must be an HTTPS search endpoint supplied by deployment configuration. Use a
 * remotely configurable proxy before exposing the public service to multiple users: the public
 * limit is application-wide, and providers must be switchable without an app update. This class
 * additionally serializes requests across its instances in this process and caches recent results.
 * It performs no requests during construction, automatic retries, or location detection.
 */
class HospitalRepositoryNominatim
internal constructor(
    private val searchEndpoint: String,
    private val transport: HospitalHttpTransport,
    private val dispatcher: CoroutineDispatcher,
    private val requestGate: HospitalRequestGate,
) : HospitalRepository {
  /** Creates a repository for the configured Nominatim-compatible HTTPS search endpoint. */
  constructor(
      searchEndpoint: String
  ) : this(searchEndpoint, UrlConnectionHospitalTransport(), Dispatchers.IO, sharedGate)

  init {
    val url = URL(searchEndpoint)
    require(
        url.protocol == "https" && url.host.isNotBlank() && url.query == null && url.ref == null
    ) {
      "A hospital search endpoint must be an HTTPS URL without query or fragment"
    }
  }

  private val cache = linkedMapOf<String, HospitalSearchResult.Success>()

  override suspend fun searchHospitals(searchText: String): HospitalSearchResult {
    val query = searchText.trim()
    if (query.isEmpty()) return HospitalSearchResult.Success(emptyList())
    return withContext(dispatcher) {
      // The shared lock also coalesces identical concurrent searches within this repository.
      requestGate.mutex.withLock {
        cache[query]?.let {
          return@withLock it
        }
        requestGate.awaitRequest()
        try {
          ensureActive()
          val encoded = URLEncoder.encode("$query [hospital]", "UTF-8")
          val response = transport.get(URL("$searchEndpoint?q=$encoded&format=jsonv2&limit=10"))
          ensureActive()
          when {
            response.status == 429 -> HospitalSearchResult.Error(HospitalSearchError.RATE_LIMITED)
            response.status !in 200..299 -> HospitalSearchResult.Error(HospitalSearchError.SERVICE)
            else ->
                parse(response.body).also { result ->
                  cache[query] = result
                  if (cache.size > 50) cache.remove(cache.keys.first())
                }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: IOException) {
          HospitalSearchResult.Error(HospitalSearchError.NETWORK)
        } catch (_: JSONException) {
          HospitalSearchResult.Error(HospitalSearchError.INVALID_RESPONSE)
        } catch (_: IllegalArgumentException) {
          HospitalSearchResult.Error(HospitalSearchError.INVALID_RESPONSE)
        } finally {
          requestGate.hasRequested = true
        }
      }
    }
  }

  private fun parse(body: String): HospitalSearchResult.Success {
    val array = JSONArray(body)
    val hospitals = buildList {
      for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        // The special phrase narrows the query; classification excludes unrelated POIs.
        if (item.getString("type") != "hospital") continue
        val category = item.getString("category")
        if (category != "amenity" && category != "healthcare") continue
        val osmType = item.getString("osm_type")
        val osmId = item.getLong("osm_id")
        val address = item.getString("display_name")
        val name = item.optString("name").ifBlank { address.substringBefore(',').trim() }
        val latitude = item.getString("lat").toDouble()
        val longitude = item.getString("lon").toDouble()
        require(osmType in setOf("node", "way", "relation") && osmId > 0)
        require(name.isNotBlank() && address.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        add(Hospital("$osmType/$osmId", name, address, latitude, longitude))
      }
    }
    return HospitalSearchResult.Success(hospitals.distinctBy { it.id })
  }

  private companion object {
    val sharedGate = HospitalRequestGate()
  }
}

/** Process-wide serialization with a conservative one-second gap after request completion. */
internal class HospitalRequestGate {
  val mutex = Mutex()
  var hasRequested = false

  suspend fun awaitRequest() {
    if (hasRequested) delay(1_000)
  }
}

internal data class HospitalHttpResponse(val status: Int, val body: String)

/** Transport seam used by local tests; never exposed to screens or ViewModels. */
internal fun interface HospitalHttpTransport {
  fun get(url: URL): HospitalHttpResponse
}

internal class UrlConnectionHospitalTransport : HospitalHttpTransport {
  override fun get(url: URL): HospitalHttpResponse {
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      // Redirects must not silently add requests or change the configured provider.
      connection.instanceFollowRedirects = false
      connection.setRequestProperty(
          "User-Agent",
          "Oncompanion/1.0 (+https://github.com/Oncompanion/Oncompanion)",
      )
      connection.setRequestProperty("Accept", "application/json")
      val status = connection.responseCode
      val body =
          if (status in 200..299) {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
              val text = StringBuilder()
              val buffer = CharArray(4096)
              var count = reader.read(buffer)
              // Bound a malformed server response before loading it into JSON memory.
              while (count != -1) {
                require(text.length + count <= 1_048_576) { "Hospital response is too large" }
                text.append(buffer, 0, count)
                count = reader.read(buffer)
              }
              text.toString()
            }
          } else ""
      return HospitalHttpResponse(status, body)
    } finally {
      connection.disconnect()
    }
  }
}
