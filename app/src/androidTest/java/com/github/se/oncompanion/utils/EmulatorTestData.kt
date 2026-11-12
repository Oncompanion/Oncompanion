// Co-authored-by: Claude Code <noreply@anthropic.com>
// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.utils

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

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

  /**
   * Creates the signed-in user's profile with [role] ("PATIENT" or "CAREGIVER") and waits for the
   * server, e.g. because joining a care circle needs a caregiver profile.
   */
  suspend fun createProfile(role: String, firstName: String = "Test") {
    val uid = checkNotNull(FirebaseEmulator.auth.currentUser) { "No user signed in" }.uid
    val profile =
        mapOf("role" to role, "firstName" to firstName, "createdAt" to FieldValue.serverTimestamp())
    withTimeout(10_000) {
      FirebaseEmulator.firestore.collection("users").document(uid).set(profile).await()
    }
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
   * Deletes a document bypassing security rules, e.g. to remove one clients can't delete.
   * [documentPath] is relative to the database root, like `users/{uid}/circle/{memberUid}`.
   */
  suspend fun deleteRawDocument(documentPath: String) {
    request("DELETE", "$FIRESTORE_REST/$documentPath", allowMissing = true)
  }

  /**
   * Removes one fresh test account's documents, remaining invitations and Auth identity. Child
   * collections are discovered: deleting a profile alone leaves its subcollections behind. Never
   * clears the emulator, which may be shared with other tests.
   */
  suspend fun deleteUserData(uid: String) {
    require(uid.isNotBlank() && '/' !in uid)
    val failures = mutableListOf<Exception>()
    suspend fun attempt(block: suspend () -> Unit) {
      try {
        block()
      } catch (error: Exception) {
        failures += error
      }
    }
    // Query by owner, including invitations whose code the UI never got a chance to display.
    attempt {
      val query =
          JSONObject(
              """{"structuredQuery":{"from":[{"collectionId":"invites"}],"where":{"fieldFilter":{"field":{"fieldPath":"ownerUid"},"op":"EQUAL","value":{"stringValue":"$uid"}}}}}"""
          )
      val results = JSONArray(request("POST", "$FIRESTORE_REST:runQuery", query.toString()))
      for (index in 0 until results.length()) {
        results.getJSONObject(index).optJSONObject("document")?.let { document ->
          attempt { deleteRawDocument(document.getString("name").substringAfter("/documents/")) }
        }
      }
    }
    // Discover this account's collections so future features cannot silently leave test data
    // behind.
    val collections =
        try {
          val ids =
              JSONObject(request("POST", "$FIRESTORE_REST/users/$uid:listCollectionIds", "{}"))
                  .optJSONArray("collectionIds") ?: JSONArray()
          (0 until ids.length()).map(ids::getString)
        } catch (error: Exception) {
          failures += error
          emptyList()
        }
    for (collection in collections) {
      attempt {
        var page = ""
        do {
          val response =
              JSONObject(
                  request(
                      "GET",
                      "$FIRESTORE_REST/users/$uid/$collection?pageSize=100&pageToken=${URLEncoder.encode(page, "UTF-8")}",
                  )
              )
          val documents = response.optJSONArray("documents") ?: JSONArray()
          for (index in 0 until documents.length()) {
            attempt {
              deleteRawDocument(
                  documents.getJSONObject(index).getString("name").substringAfter("/documents/")
              )
            }
          }
          page = response.optString("nextPageToken")
        } while (page.isNotEmpty())
      }
    }
    attempt { deleteRawDocument("users/$uid") }
    attempt {
      request(
          "POST",
          "http://${FirebaseEmulator.HOST}:${FirebaseEmulator.AUTH_PORT}/identitytoolkit.googleapis.com/v1/accounts:delete",
          JSONObject().put("localId", uid).toString(),
          allowMissing = true,
      )
    }
    if (failures.isNotEmpty()) {
      throw AssertionError("Couldn't clean up the test account", failures.first()).apply {
        failures.drop(1).forEach(::addSuppressed)
      }
    }
  }

  /**
   * Replaces a whole document bypassing security rules, like [createRawDocument]: what another
   * device would do while this one is offline.
   */
  suspend fun replaceRawDocument(collection: String, documentId: String, fieldsJson: String) {
    request("PATCH", "$FIRESTORE_REST/$collection/$documentId", body = "{\"fields\": $fieldsJson}")
  }

  /** Retries a few times: the emulator's host loopback can refuse the very first connections. */
  private suspend fun request(
      method: String,
      url: String,
      body: String? = null,
      allowMissing: Boolean = false,
  ): String {
    var attempt = 0
    while (true) {
      try {
        return requestOnce(method, url, body, allowMissing)
      } catch (e: IOException) {
        if (++attempt >= 5) throw e
        delay(1_000)
      }
    }
  }

  private suspend fun requestOnce(
      method: String,
      url: String,
      body: String?,
      allowMissing: Boolean,
  ): String =
      withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = method
          connection.connectTimeout = 10_000
          connection.readTimeout = 10_000
          connection.setRequestProperty("Authorization", "Bearer owner")
          if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
          }
          val code = connection.responseCode
          if (allowMissing && code == 404) return@withContext ""
          val error =
              if (code !in 200..299) connection.errorStream?.bufferedReader()?.use { it.readText() }
              else null
          if (allowMissing && code == 400 && error?.contains("USER_NOT_FOUND") == true)
              return@withContext ""
          check(code in 200..299) {
            "$method emulator request failed with HTTP $code: ${error.orEmpty()}"
          }
          connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
          connection.disconnect()
        }
      }
}
