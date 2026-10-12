package com.github.se.oncompanion.model.hospital

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HospitalHttpTransportTest {
  @Test
  fun identifiesApplicationSetsTimeoutsAndClosesSuccessfulResponse() {
    val connection = TestConnection()
    connection.body = "[{\"name\":\"Hôpital\"}]"
    assertEquals(
        HospitalHttpResponse(200, connection.body),
        UrlConnectionHospitalTransport().get(url(connection)),
    )
    assertEquals("GET", connection.requestMethod)
    assertEquals(10_000, connection.connectTimeout)
    assertEquals(10_000, connection.readTimeout)
    assertFalse(connection.instanceFollowRedirects)
    assertEquals(
        "Oncompanion/1.0 (+https://github.com/Oncompanion/Oncompanion)",
        connection.getRequestProperty("User-Agent"),
    )
    assertEquals("application/json", connection.getRequestProperty("Accept"))
    assertTrue(connection.disconnected)
  }

  @Test
  fun doesNotReadAnErrorOrRedirectResponseAndAlwaysDisconnects() {
    listOf(302, 429, 503).forEach { status ->
      val connection = TestConnection()
      connection.status = status
      assertEquals(
          HospitalHttpResponse(status, ""),
          UrlConnectionHospitalTransport().get(url(connection)),
      )
      assertFalse(connection.wasRead)
      assertTrue(connection.disconnected)
    }
  }

  @Test
  fun disconnectsOnNetworkFailure() {
    val connection = TestConnection()
    connection.fail = true
    try {
      UrlConnectionHospitalTransport().get(url(connection))
      throw AssertionError("Expected network error")
    } catch (_: IOException) {
      assertTrue(connection.disconnected)
    }
  }

  @Test
  fun rejectsOversizedResponsesAndDisconnects() {
    val connection = TestConnection()
    connection.body = "x".repeat(1_048_577)
    try {
      UrlConnectionHospitalTransport().get(url(connection))
      throw AssertionError("Expected response size error")
    } catch (_: IllegalArgumentException) {
      assertTrue(connection.disconnected)
    }
  }

  private fun url(connection: TestConnection): URL =
      URL(
          null,
          "https://example.invalid/search",
          object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection = connection
          },
      )

  private class TestConnection : HttpURLConnection(URL("https://example.invalid/search")) {
    var body = "[]"
    var status = 200
    var disconnected = false
    var wasRead = false
    var fail = false

    override fun getResponseCode(): Int {
      if (fail) throw IOException("Offline")
      return status
    }

    override fun getInputStream(): ByteArrayInputStream {
      wasRead = true
      return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    }

    override fun disconnect() {
      disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun connect() {}
  }
}
