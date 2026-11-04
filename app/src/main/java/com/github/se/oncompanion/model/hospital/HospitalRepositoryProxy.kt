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
          // Recheck after in-flight work: repeated queries share success, including offline/empty
          // results.
          cache[query]?.let {
            return@withLock it
          }
          cooldown?.let { failure ->
            if (retryAt?.hasPassedNow() != true) return@withLock failure
            cooldown = null
            retryAt = null
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
              val failure =
                  HospitalSearchResult.Error(
                      when (result.kind) {
                        HospitalProxyError.NETWORK,
                        HospitalProxyError.TIMEOUT -> HospitalSearchError.NETWORK
                        HospitalProxyError.INVALID_RESPONSE -> HospitalSearchError.INVALID_RESPONSE
                        HospitalProxyError.RATE_LIMITED -> HospitalSearchError.RATE_LIMITED
                        else -> HospitalSearchError.SERVICE
                      }
                  )
              // A response's cooldown covers all queries; only explicit actions after expiry can
              // send again.
              when (val retry = result.retryAfter) {
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
              failure
            }
          }
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
