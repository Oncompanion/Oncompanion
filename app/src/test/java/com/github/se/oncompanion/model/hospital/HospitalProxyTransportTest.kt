// Co-authored-by: ChatGPT <noreply@openai.com>
package com.github.se.oncompanion.model.hospital

import com.github.se.oncompanion.model.session.SessionAccess
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HospitalProxyTransportTest {
  @Test
  fun attachesOnlyExpectedHeadersAndClosesUtf8Success() = runTest {
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
  fun doesNotReadErrorOrRedirectBodiesAndClosesTheErrorStream() = runTest {
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
  fun alwaysDisconnectsAfterNetworkTimeoutAndOversizedResponseFailures() = runTest {
    for (failure in listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
      val connection = FakeConnection()
      connection.failure = failure
      assertThrows(failure.javaClass) {
        runBlocking { UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap()) }
      }
      assertTrue(connection.disconnected)
    }
    val connection = FakeConnection()
    connection.body = "é".repeat(131073)
    assertThrows(IllegalArgumentException::class.java) {
      runBlocking { UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap()) }
    }
    assertTrue(connection.closed)
    assertTrue(connection.disconnected)
  }

  @Test
  fun acceptsTheExactBackendByteLimitAndMissingRetryHeader() = runTest {
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
  fun errorStreamCloseFailureStillDisconnects() = runTest {
    val connection = FakeConnection()
    connection.status = 503
    connection.closeFailure = true
    assertThrows(IOException::class.java) {
      runBlocking { UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap()) }
    }
    assertTrue(connection.disconnected)
  }

  @Test
  fun cancellationDisconnectsAnActiveReadAndClosesItsStream() = runTest {
    for (freeze in listOf(false, true)) {
      val connection = FakeConnection()
      connection.blockRead = true
      val pending = async {
        SessionAccess.operation {
          UrlConnectionHospitalProxyTransport().get(url(connection), emptyMap())
        }
      }
      try {
        connection.readStarted.await()
        if (freeze) SessionAccess.freeze() else pending.cancel()
        pending.join()
        assertTrue(pending.isCancelled)
        assertTrue(connection.disconnected)
        connection.readClosed.await()
        assertTrue(connection.closed)
      } finally {
        connection.releaseRead.countDown()
        SessionAccess.resume()
      }
    }
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
    var blockRead = false
    val readStarted = CompletableDeferred<Unit>()
    val readClosed = CompletableDeferred<Unit>()
    val releaseRead = CountDownLatch(1)
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
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
          if (blockRead) {
            readStarted.complete(Unit)
            check(releaseRead.await(5, TimeUnit.SECONDS)) { "Read was not disconnected" }
          }
          return super.read(buffer, offset, length)
        }

        override fun close() {
          closed = true
          readClosed.complete(Unit)
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
      releaseRead.countDown()
    }

    override fun connect() {}

    override fun usingProxy(): Boolean = false
  }
}
