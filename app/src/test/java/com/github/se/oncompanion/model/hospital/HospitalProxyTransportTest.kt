// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.*
import org.junit.Test

class HospitalProxyTransportTest {
  @Test
  fun attachesOnlyExpectedHeadersAndClosesUtf8Success() {
    val connection = FakeConnection()
    connection.body = "[\"Hôpital\"]"
    val headers = mapOf("Authorization" to "Bearer test", "X-Firebase-AppCheck" to "test-app")
    assertEquals(
        HospitalProxyResponse(200, connection.body, "90"),
        UrlConnectionHospitalProxyTransport().get(url(connection), headers),
    )
    assertEquals("GET", connection.requestMethod)
    assertEquals(10_000, connection.connectTimeout)
    assertEquals(10_000, connection.readTimeout)
    assertFalse(connection.instanceFollowRedirects)
    assertFalse(connection.useCaches)
    assertEquals("application/json", connection.getRequestProperty("Accept"))
    headers.forEach { (key, value) -> assertEquals(value, connection.getRequestProperty(key)) }
    assertTrue(connection.closed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun doesNotReadErrorOrRedirectBodiesAndClosesTheErrorStream() {
    for (status in listOf(204, 301, 302, 401, 403, 429, 500, 503)) {
      val connection = FakeConnection()
      connection.status = status
      assertEquals(
          HospitalProxyResponse(status, "", "90"),
          UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap()),
      )
      assertFalse("status $status", connection.wasRead)
      assertTrue(connection.errorClosed)
      assertTrue(connection.disconnected)
    }
  }

  @Test
  fun alwaysDisconnectsAfterNetworkTimeoutAndOversizedResponseFailures() {
    for (failure in listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
      val connection = FakeConnection()
      connection.failure = failure
      assertThrows(failure.javaClass) {
        UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap())
      }
      assertTrue(connection.disconnected)
    }
    val connection = FakeConnection()
    connection.body = "é".repeat(131073)
    assertThrows(IllegalArgumentException::class.java) {
      UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap())
    }
    assertTrue(connection.closed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun acceptsTheExactBackendByteLimitAndMissingRetryHeader() {
    val connection = FakeConnection()
    connection.body = "é".repeat(131072)
    connection.retry = null
    assertEquals(
        HospitalProxyResponse(200, connection.body),
        UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap()),
    )
    assertTrue(connection.closed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun errorStreamCloseFailureStillDisconnects() {
    val connection = FakeConnection()
    connection.status = 503
    connection.closeFailure = true
    assertThrows(IOException::class.java) {
      UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap())
    }
    assertTrue(connection.disconnected)
  }

  private fun url(connection: FakeConnection): URL =
      URL(
          null,
          "https://proxy.example.invalid/search",
          object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = connection
          },
      )

  private class FakeConnection : HttpURLConnection(URL("https://proxy.example.invalid/search")) {
    var status = 200
    var body = "[]"
    var retry: String? = "90"
    var failure: IOException? = null
    var wasRead = false
    var closed = false
    var errorClosed = false
    var disconnected = false
    var closeFailure = false

    override fun getResponseCode(): Int {
      failure?.let { throw it }
      return status
    }

    override fun getHeaderField(name: String): String? = if (name == "Retry-After") retry else null

    override fun getInputStream(): ByteArrayInputStream {
      wasRead = true
      return object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
        override fun close() {
          closed = true
          super.close()
        }
      }
    }

    override fun getErrorStream(): ByteArrayInputStream =
        object : ByteArrayInputStream(ByteArray(0)) {
          override fun close() {
            errorClosed = true
            if (closeFailure) throw IOException("close")
            super.close()
          }
        }

    override fun disconnect() {
      disconnected = true
    }

    override fun connect() {}

    override fun usingProxy(): Boolean = false
  }
}
