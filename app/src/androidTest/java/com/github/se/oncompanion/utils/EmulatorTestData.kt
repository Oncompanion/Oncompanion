package com.github.se.oncompanion.utils

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Test data helpers for the Firebase emulators. Emulator data is never wiped (other test runs may
 * share the emulators): every account gets a unique email, hence a fresh uid.
 */
object EmulatorTestData {
  const val PROJECT_ID = "oncompanion-c4144"
  private const val PASSWORD = "password123"
  private const val FIRESTORE_REST =
      "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.FIRESTORE_PORT}" +
          "/v1/projects/$PROJECT_ID/databases/(default)/documents"

  private val emails = mutableMapOf<String, String>()

  fun signOut() = FirebaseEmulator.auth.signOut()

  /**
   * Signs out and waits until Firestore uses the signed-out credentials. Firestore picks up auth
   * changes asynchronously, and a write sent before that is queued for the previous user and never
   * reaches the server while signed out (the test would time out instead of being denied).
   * [probeUid] must be a profile only its owner can read.
   */
  suspend fun signOutAndWaitForFirestore(probeUid: String) {
    signOut()
    val probe = FirebaseEmulator.firestore.collection("users").document(probeUid)
    withTimeout(10_000) {
      while (true) {
        try {
          probe.get(Source.SERVER).await()
        } catch (e: FirebaseFirestoreException) {
          if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return@withTimeout
        }
        delay(100)
      }
    }
  }

  /** Creates a fresh email/password account and leaves it signed in. Returns its uid. */
  suspend fun createUser(name: String): String {
    val auth = FirebaseEmulator.auth
    auth.signOut()
    val email = "$name-${UUID.randomUUID()}@example.com"
    emails[name] = email
    return auth.createUserWithEmailAndPassword(email, PASSWORD).await().user!!.uid
  }

  /** Signs in the account last created with [createUser] for [name]. Returns its uid. */
  suspend fun signIn(name: String): String {
    val auth = FirebaseEmulator.auth
    auth.signOut()
    val email = checkNotNull(emails[name]) { "No user created for $name" }
    return auth.signInWithEmailAndPassword(email, PASSWORD).await().user!!.uid
  }

  /**
   * Creates a raw document bypassing security rules (emulator "owner" token), to produce documents
   * the client SDK is not allowed to write. [fieldsJson] is the Firestore REST `fields` object.
   */
  suspend fun createRawDocument(collection: String, documentId: String, fieldsJson: String) {
    request(
        "POST",
        "$FIRESTORE_REST/$collection?documentId=$documentId",
        body = "{\"fields\": $fieldsJson}",
    )
  }

  /**
   * Replaces a whole document bypassing security rules, like [createRawDocument]: what another
   * device would do while this one is offline.
   */
  suspend fun replaceRawDocument(collection: String, documentId: String, fieldsJson: String) {
    request("PATCH", "$FIRESTORE_REST/$collection/$documentId", body = "{\"fields\": $fieldsJson}")
  }

  /** Retries a few times: the emulator's host loopback can refuse the very first connections. */
  private suspend fun request(method: String, url: String, body: String? = null) {
    var attempt = 0
    while (true) {
      try {
        return requestOnce(method, url, body)
      } catch (e: IOException) {
        if (++attempt >= 5) throw e
        delay(1_000)
      }
    }
  }

  private suspend fun requestOnce(method: String, url: String, body: String?) =
      withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = method
          connection.setRequestProperty("Authorization", "Bearer owner")
          if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
          }
          val code = connection.responseCode
          check(code in 200..299) {
            "$method $url failed with HTTP $code: " +
                (connection.errorStream?.bufferedReader()?.readText() ?: "")
          }
        } finally {
          connection.disconnect()
        }
      }
}
