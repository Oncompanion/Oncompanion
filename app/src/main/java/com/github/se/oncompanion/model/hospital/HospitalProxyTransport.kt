// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class HospitalProxyResponse(
    val status: Int,
    val body: String,
    val retryAfter: String? = null,
)

/** Cancellable HTTP boundary; tests replace the connection or response. */
internal fun interface HospitalProxyTransport {
  suspend fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse
}

/**
 * Same platform stack as the existing repository, extended for credentials and response headers.
 */
internal class UrlConnectionHospitalProxyTransport : HospitalProxyTransport {
  override suspend fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse =
      suspendCancellableCoroutine { continuation ->
        val connection = url.openConnection() as HttpURLConnection
        // Disconnect immediately rather than waiting for the blocking read timeout at sign-out.
        continuation.invokeOnCancellation { connection.disconnect() }
        Dispatchers.IO.dispatch(continuation.context) {
          val outcome =
              try {
                continuation.context.ensureActive()
                Result.success(read(connection, headers, continuation.context))
              } catch (failure: Exception) {
                Result.failure(failure)
              } finally {
                connection.disconnect()
              }
          // CancellableContinuation discards completion racing with cancellation.
          continuation.resumeWith(outcome)
        }
      }

  private fun read(
      connection: HttpURLConnection,
      headers: Map<String, String>,
      context: CoroutineContext,
  ): HospitalProxyResponse {

    connection.requestMethod = "GET"
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.setRequestProperty("Accept", "application/json")
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    context.ensureActive()
    val status = connection.responseCode
    val body =
        if (status == 200)
            connection.inputStream.use { stream ->
              val output = ByteArrayOutputStream()
              val buffer = ByteArray(4096)
              var count = stream.read(buffer)
              while (count != -1) {
                context.ensureActive()
                require(output.size() + count <= 262144)
                output.write(buffer, 0, count)
                count = stream.read(buffer)
              }
              output.toString("UTF-8")
            }
        else {
          // Error payloads are untrusted and unnecessary for centralized status mapping.
          connection.errorStream?.close()
          ""
        }
    return HospitalProxyResponse(status, body, connection.getHeaderField("Retry-After"))
  }
}
