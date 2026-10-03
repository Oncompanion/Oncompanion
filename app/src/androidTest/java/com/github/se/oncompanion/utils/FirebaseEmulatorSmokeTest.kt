package com.github.se.oncompanion.utils

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks that instrumented tests reach the Firebase emulators (see firebase.json and the CI
 * workflow): an account can be created on the Auth emulator, and the signed-in user can write and
 * read their own data on the Firestore emulator.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseEmulatorSmokeTest {

  @After
  fun tearDown() {
    EmulatorTestData.signOut()
  }

  @Test
  fun authEmulatorCreatesAndSignsInAccounts(): Unit = runBlocking {
    val uid = EmulatorTestData.createUser("smoke")

    assertTrue(uid.isNotBlank())
    assertEquals(uid, FirebaseEmulator.auth.currentUser?.uid)
  }

  @Test
  fun firestoreEmulatorStoresTheSignedInUsersData(): Unit = runBlocking {
    val uid = EmulatorTestData.createUser("smoke")
    val doc =
        FirebaseEmulator.firestore
            .collection("users")
            .document(uid)
            .collection("smoke")
            .document("ping")

    withTimeout(10_000) {
      doc.set(mapOf("value" to 42L)).await()
      val read = doc.get(Source.SERVER).await()
      assertEquals(42L, read.getLong("value"))
    }
  }
}
