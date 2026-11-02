// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class HospitalProxyResponse(
    val status: Int,
    val body: String,
    val retryAfter: String? = null,
)

/**
 * Blocking boundary: the client supplies an IO dispatcher; tests replace the connection or
 * response.
 */
internal fun interface HospitalProxyTransport {
  fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse
}

/**
 * Same platform stack as the existing repository, extended for credentials and response headers.
 */
internal class UrlConnectionHospitalProxyTransport : HospitalProxyTransport {
  override fun get(url: URL, headers: Map<String, String>): HospitalProxyResponse {
    val connection = url.openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.instanceFollowRedirects = false
      connection.useCaches = false
      connection.setRequestProperty("Accept", "application/json")
      headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
      val status = connection.responseCode
      val body =
          if (status == 200)
              connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var count = stream.read(buffer)
                while (count != -1) {
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
    } finally {
      connection.disconnect()
    }
  }
}
