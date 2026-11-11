// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies cleanup of partial journeys without deleting another test's account or invitation. */
@RunWith(AndroidJUnit4::class)
class EmulatorTestDataCleanupTest {
  /** Covers pre-onboarding, pending and redeemed runs, including repeated cleanup after failure. */
  @Test
  fun cleanup_removesOnlyItsAccountAtEachJourneyStage(): Unit = runBlocking {
    val survivor = EmulatorTestData.createUser("m2-cleanup-survivor")
    val survivorInvite = "cleanup-survivor-$survivor"
    try {
      EmulatorTestData.createProfile("PATIENT")
      EmulatorTestData.createRawDocument("users/$survivor/$futureCollection", "entry", "{}")
      EmulatorTestData.createRawDocument("invites", survivorInvite, ownerFields(survivor))
      for (stage in listOf("before-profile", "pending", "joined")) {
        val uid = EmulatorTestData.createUser("m2-cleanup-$stage")
        val invite = "cleanup-$uid"
        try {
          if (stage != "before-profile") {
            EmulatorTestData.createProfile("PATIENT")
            for (collection in collections + futureCollection) {
              EmulatorTestData.createRawDocument("users/$uid/$collection", "entry", "{}")
            }
          }
          if (stage == "pending") {
            EmulatorTestData.createRawDocument("invites", invite, ownerFields(uid))
          }
          // A run can leave more than one REST page of entries; every page must be removed.
          if (stage == "joined") {
            for (index in 0..100) {
              EmulatorTestData.createRawDocument("users/$uid/symptoms", "page-$index", "{}")
            }
          }
          EmulatorTestData.signOut()
          EmulatorTestData.deleteUserData(uid)
          EmulatorTestData.deleteUserData(uid)

          assertEquals(404, documentStatus("users/$uid"))
          for (collection in collections + futureCollection) {
            assertEquals(404, documentStatus("users/$uid/$collection/entry"))
          }
          assertEquals(404, documentStatus("invites/$invite"))
          if (stage == "joined") {
            for (index in listOf(0, 50, 99, 100)) {
              assertEquals(404, documentStatus("users/$uid/symptoms/page-$index"))
            }
          }
          val identities = authUids(uid, survivor)
          assertFalse("The test's Auth account must be removed", uid in identities)
          assertTrue("Another test's Auth account must survive", survivor in identities)
          assertEquals(200, documentStatus("users/$survivor"))
          assertEquals(200, documentStatus("users/$survivor/$futureCollection/entry"))
          assertEquals(200, documentStatus("invites/$survivorInvite"))
        } finally {
          EmulatorTestData.signOut()
          EmulatorTestData.deleteUserData(uid)
        }
      }
    } finally {
      EmulatorTestData.signOut()
      EmulatorTestData.deleteUserData(survivor)
    }
  }

  /** A rejected raw write reports the emulator's explanation, not just an HTTP status. */
  @Test
  fun rawRequestFailure_includesEmulatorExplanation(): Unit = runBlocking {
    val path = "users/cleanup-error-${UUID.randomUUID()}/symptoms"
    val failure = runCatching {
      EmulatorTestData.createRawDocument(path, "invalid", "not-json")
    }
        .exceptionOrNull()
    assertTrue("Malformed JSON must be rejected", failure is IllegalStateException)
    assertTrue(
        "The failure must include the emulator's response body",
        failure?.message.orEmpty().substringAfter("HTTP 400: ", "").isNotBlank(),
    )
  }

  private fun ownerFields(uid: String) =
      JSONObject().put("ownerUid", JSONObject().put("stringValue", uid)).toString()

  // Independent REST reads verify remote deletion, rather than a stale SDK cache or signed-out UI.
  private suspend fun documentStatus(path: String): Int =
      withContext(Dispatchers.IO) {
        val connection =
            URL(
                    "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}/v1/projects/${EmulatorTestData.PROJECT_ID}/databases/(default)/documents/$path"
                )
                .openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000
          connection.readTimeout = 10_000
          connection.setRequestProperty("Authorization", "Bearer owner")
          connection.responseCode
        } finally {
          connection.disconnect()
        }
      }

  private suspend fun authUids(vararg uids: String): Set<String> =
      withContext(Dispatchers.IO) {
        val connection =
            URL(
                    "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.AUTH_PORT}/identitytoolkit.googleapis.com/v1/accounts:lookup"
                )
                .openConnection() as HttpURLConnection
        try {
          connection.connectTimeout = 10_000
          connection.readTimeout = 10_000
          connection.requestMethod = "POST"
          connection.doOutput = true
          connection.setRequestProperty("Authorization", "Bearer owner")
          connection.setRequestProperty("Content-Type", "application/json")
          connection.outputStream.use {
            it.write(JSONObject().put("localId", JSONArray(uids.toList())).toString().toByteArray())
          }
          check(connection.responseCode == 200)
          val accounts =
              JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                  .optJSONArray("users") ?: JSONArray()
          (0 until accounts.length())
              .map { accounts.getJSONObject(it).getString("localId") }
              .toSet()
        } finally {
          connection.disconnect()
        }
      }

  private companion object {
    // This fictional future feature must be cleaned without being named by the cleanup helper.
    const val futureCollection = "cleanupFutureFeature"
    val collections =
        listOf(
            "symptoms",
            "circle",
            "patients",
            "prescriptions",
            "appointments",
            "eventRegistrations",
        )
  }
}
