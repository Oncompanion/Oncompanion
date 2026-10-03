package com.github.se.oncompanion.utils

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONObject

/** Connects the Firebase SDKs to the local emulators (see firebase.json). Shared by all tests. */
object FirebaseEmulator {
  const val HOST = "10.0.2.2"
  const val AUTH_PORT = 9099
  const val FIRESTORE_PORT = 8080

  @Volatile private var connected = false

  val auth: FirebaseAuth
    get() {
      ensureConnected()
      return FirebaseAuth.getInstance()
    }

  val firestore: FirebaseFirestore
    get() {
      ensureConnected()
      return FirebaseFirestore.getInstance()
    }

  /** Must run before any other use of the Auth / Firestore instances in this process. */
  @Synchronized
  fun ensureConnected() {
    if (connected) return
    FirebaseAuth.getInstance().useEmulator(HOST, AUTH_PORT)
    FirebaseFirestore.getInstance().useEmulator(HOST, FIRESTORE_PORT)
    connected = true
  }

  /** An unsigned Google ID token accepted by the Auth emulator. */
  fun fakeGoogleIdToken(
      sub: String,
      email: String,
      name: String? = null,
      givenName: String? = null,
  ): String {
    val claims =
        JSONObject().apply {
          put("sub", sub)
          put("email", email)
          put("email_verified", true)
          if (name != null) put("name", name)
          if (givenName != null) put("given_name", givenName)
        }
    return claims.toString()
  }
}
