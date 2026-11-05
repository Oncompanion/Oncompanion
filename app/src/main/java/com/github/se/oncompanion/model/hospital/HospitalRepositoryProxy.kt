// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.github.se.oncompanion.model.session.SessionAccess
import java.net.URL
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared public-result cache and retry gate around the authenticated proxy client. */
class HospitalRepositoryProxy
internal constructor(
    private val client: HospitalProxyClient,
    private val timeSource: TimeSource,
) : HospitalRepository {
  /**
   * Creates an inert adapter for a trusted deployed proxy; never falls back to public Nominatim.
   */
  constructor(endpoint: String) : this(proxyClient(endpoint), TimeSource.Monotonic)

  private val mutex = Mutex()
  private val cache = linkedMapOf<String, HospitalSearchResult.Success>()
  private var cooldown: HospitalSearchResult.Error? = null
  private var retryAt: TimeMark? = null

  override suspend fun searchHospitals(searchText: String): HospitalSearchResult =
      SessionAccess.operation {
        val query = searchText.trim()
        if (query.isEmpty()) return@operation HospitalSearchResult.Success(emptyList())
        mutex.withLock {
          currentCoroutineContext().ensureActive()
          cachedOrCooldown(query)?.let {
            return@withLock it
          }
          val result = client.search(query)
          currentCoroutineContext().ensureActive()
          when (result) {
            is HospitalProxyResult.Success -> {
              val success = HospitalSearchResult.Success(result.hospitals.distinctBy { it.id })
              cache[query] = success
              if (cache.size > 50) cache.remove(cache.keys.first())
              success
            }
            is HospitalProxyResult.Failure -> {
              val failure = HospitalSearchResult.Error(result.kind.toSearchError())
              rememberCooldown(result.retryAfter, failure)
              failure
            }
          }
        }
      }

  // Cache hits remain usable even while uncached requests are blocked.
  private fun cachedOrCooldown(query: String): HospitalSearchResult? {
    cache[query]?.let {
      return it
    }
    val failure = cooldown ?: return null
    if (retryAt?.hasPassedNow() != true) return failure
    cooldown = null
    retryAt = null
    return null
  }

  private fun HospitalProxyError.toSearchError(): HospitalSearchError =
      when (this) {
        HospitalProxyError.NETWORK,
        HospitalProxyError.TIMEOUT -> HospitalSearchError.NETWORK
        HospitalProxyError.INVALID_RESPONSE -> HospitalSearchError.INVALID_RESPONSE
        HospitalProxyError.RATE_LIMITED -> HospitalSearchError.RATE_LIMITED
        else -> HospitalSearchError.SERVICE
      }

  // Only a later explicit search can send again; no timer retries requests.
  private fun rememberCooldown(
      retry: HospitalProxyRetryAfter?,
      failure: HospitalSearchResult.Error,
  ) {
    when (retry) {
      is HospitalProxyRetryAfter.Delay -> {
        retryAt = timeSource.markNow() + retry.duration.toKotlinDuration()
        cooldown = failure
      }
      HospitalProxyRetryAfter.BeyondLimit -> {
        retryAt = null
        cooldown = failure
      }
      null -> Unit
    }
  }

  private companion object {
    fun proxyClient(endpoint: String): HospitalProxyClient {
      val host = URL(endpoint).host.lowercase().trimEnd('.')
      require(
          host != "nominatim.openstreetmap.org" && !host.endsWith(".nominatim.openstreetmap.org")
      ) {
        "Configure the authenticated hospital proxy"
      }
      return HospitalProxyClient(endpoint)
    }
  }
}
