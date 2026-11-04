// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.github.se.oncompanion.model.session.SessionAccess
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener

/** Transport outcome only; callers decide presentation, persistence and subsequent user actions. */
sealed interface HospitalProxyResult {
  /** Validated hospital values, including an empty successful search. */
  data class Success(val hospitals: List<Hospital>) : HospitalProxyResult

  /** No sensitive server body/credentials are retained. A retry hint never schedules a request. */
  data class Failure(
      val kind: HospitalProxyError,
      val status: Int? = null,
      val retryAfter: HospitalProxyRetryAfter? = null,
  ) : HospitalProxyResult
}

/** Distinguishes token, HTTP, transport and schema failures without leaking exception details. */
enum class HospitalProxyError {
  AUTHENTICATION,
  APP_CHECK,
  ENDPOINT,
  INVALID_REQUEST,
  RATE_LIMITED,
  UNAVAILABLE,
  SERVER,
  UNEXPECTED_STATUS,
  NETWORK,
  TIMEOUT,
  INVALID_RESPONSE,
}

/** A bounded server delay, or an explicit signal that no safe bounded delay can be represented. */
sealed interface HospitalProxyRetryAfter {
  /** Positive delay; this is a hint, not permission to bypass the server's coordinator. */
  data class Delay(val duration: Duration) : HospitalProxyRetryAfter

  /** Server requested more than one day; callers must not shorten it to a retryable delay. */
  data object BeyondLimit : HospitalProxyRetryAfter
}

/** Independent authenticated GET client. It has no repository/provider/UI wiring or retry loop. */
class HospitalProxyClient
internal constructor(
    private val endpoint: String,
    private val tokens: HospitalProxyTokens,
    private val transport: HospitalProxyTransport,
    private val dispatcher: CoroutineDispatcher,
    private val clock: Clock,
) {
  /** Uses platform networking and a trusted deployment-supplied HTTPS endpoint; no default URL. */
  constructor(
      endpoint: String,
      tokens: HospitalProxyTokens = FirebaseHospitalProxyTokens(),
  ) : this(
      endpoint,
      tokens,
      UrlConnectionHospitalProxyTransport(),
      Dispatchers.IO,
      Clock.systemUTC(),
  )

  init {
    val url = URL(endpoint)
    require(
        url.protocol == "https" &&
            url.host.isNotBlank() &&
            url.userInfo == null &&
            url.query == null &&
            url.ref == null
    )
    url.toURI()
  }

  /**
   * Explicit public hospital/location text only. Cancellation propagates through session cleanup.
   */
  suspend fun search(text: String): HospitalProxyResult = SessionAccess.operation {
    withContext(dispatcher) {
      val query = text.trim()
      if (query.isEmpty()) return@withContext HospitalProxyResult.Success(emptyList())
      if (query.length > 200)
          return@withContext HospitalProxyResult.Failure(HospitalProxyError.INVALID_REQUEST)
      var tokenError = HospitalProxyError.AUTHENTICATION
      val headers =
          try {
            val auth = tokens.idToken()
            if (!validToken(auth, 8185)) return@withContext HospitalProxyResult.Failure(tokenError)
            tokenError = HospitalProxyError.APP_CHECK
            val app = tokens.appCheckToken()
            if (!validToken(app)) return@withContext HospitalProxyResult.Failure(tokenError)
            mapOf("Authorization" to "Bearer $auth", "X-Firebase-AppCheck" to app!!)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            return@withContext HospitalProxyResult.Failure(tokenError)
          }
      try {
        currentCoroutineContext().ensureActive()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val response =
            transport.get(
                URL("$endpoint?q=$encoded&format=jsonv2&limit=10"),
                headers,
            )
        currentCoroutineContext().ensureActive()
        if (response.status == 200) parse(response.body)
        else
            HospitalProxyResult.Failure(
                statusError(response.status),
                response.status,
                if (response.status == 429 || response.status == 503)
                    parseRetryAfter(response.retryAfter, clock)
                else null,
            )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: SocketTimeoutException) {
        HospitalProxyResult.Failure(HospitalProxyError.TIMEOUT)
      } catch (_: IOException) {
        HospitalProxyResult.Failure(HospitalProxyError.NETWORK)
      } catch (_: JSONException) {
        HospitalProxyResult.Failure(HospitalProxyError.INVALID_RESPONSE)
      } catch (_: IllegalArgumentException) {
        HospitalProxyResult.Failure(HospitalProxyError.INVALID_RESPONSE)
      }
    }
  }

  private fun validToken(value: String?, maxLength: Int = 8192): Boolean =
      !value.isNullOrEmpty() && value.length <= maxLength && value.all { it in '!'..'~' }

  private fun parse(body: String): HospitalProxyResult.Success {
    val parser = JSONTokener(body)
    val array = parser.nextValue() as? JSONArray ?: throw JSONException("Expected array")
    require(parser.nextClean() == '\u0000' && array.length() <= 10)
    val hospitals =
        (0 until array.length()).map { index ->
          val item = array.getJSONObject(index)
          fun string(field: String) =
              item.get(field) as? String ?: throw JSONException("Expected string")
          require(
              string("type") == "hospital" && string("category") in setOf("amenity", "healthcare")
          )
          val type = string("osm_type")
          val id = item.get("osm_id")
          val name = string("name")
          val address = string("display_name")
          val lat = string("lat").toDouble()
          val lon = string("lon").toDouble()
          require(type in setOf("node", "way", "relation") && (id is Int || id is Long))
          require((id as Number).toLong() in 1..9_007_199_254_740_991L)
          require(name.isNotBlank() && address.isNotBlank())
          require(lat.isFinite() && lat in -90.0..90.0 && lon.isFinite() && lon in -180.0..180.0)
          Hospital("$type/$id", name, address, lat, lon)
        }
    return HospitalProxyResult.Success(hospitals)
  }
}

private fun statusError(status: Int): HospitalProxyError =
    when (status) {
      401 -> HospitalProxyError.AUTHENTICATION
      403 -> HospitalProxyError.APP_CHECK
      404 -> HospitalProxyError.ENDPOINT
      429 -> HospitalProxyError.RATE_LIMITED
      503 -> HospitalProxyError.UNAVAILABLE
      in 400..499 -> HospitalProxyError.INVALID_REQUEST
      in 500..599 -> HospitalProxyError.SERVER
      else -> HospitalProxyError.UNEXPECTED_STATUS
    }

/** Parses HTTP delays without overflow or silently shortening an excessive provider wait. */
internal fun parseRetryAfter(raw: String?, clock: Clock): HospitalProxyRetryAfter? {
  val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val seconds =
      if (value.all { it in '0'..'9' }) {
        value.toLongOrNull() ?: return HospitalProxyRetryAfter.BeyondLimit
      } else
          try {
            val deadline =
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            val remaining = Duration.between(clock.instant(), deadline)
            // Round fractional remaining seconds up so HTTP dates cannot shorten a cooldown.
            remaining.seconds + if (remaining.nano > 0) 1 else 0
          } catch (_: DateTimeParseException) {
            return null
          }
  return when {
    seconds <= 0 -> null
    seconds > 86400 -> HospitalProxyRetryAfter.BeyondLimit
    else -> HospitalProxyRetryAfter.Delay(Duration.ofSeconds(seconds))
  }
}
