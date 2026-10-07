package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests firestore.rules for /users/{uid}/circle/{memberUid} through the client SDK. */
@RunWith(AndroidJUnit4::class)
class CareCircleSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    EmulatorTestData.createUser("bob")
    aliceUid = EmulatorTestData.createUser("alice") // alice stays signed in
    // Clients can't write to the circle yet, so the member is written bypassing the rules
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/$COLLECTION",
        MEMBER_UID,
        """{"firstName": {"stringValue": "Sophie"}}""",
    )
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  private fun circle(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(COLLECTION)

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- read ----

  @Test
  fun ownerCanRead(): Unit = runBlocking {
    assertTrue(allowed(circle(aliceUid).document(MEMBER_UID).get(Source.SERVER)).exists())
    assertEquals(1, allowed(circle(aliceUid).get(Source.SERVER)).size())
  }

  @Test
  fun otherUserCannotRead(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(circle(aliceUid).document(MEMBER_UID).get(Source.SERVER))
    denied(circle(aliceUid).get(Source.SERVER))
  }

  @Test
  fun signedOutCannotRead(): Unit = runBlocking {
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(circle(aliceUid).get(Source.SERVER))
  }

  // ---- members can't be written yet ----

  @Test
  fun ownerCannotWrite(): Unit = runBlocking {
    val member = circle(aliceUid).document(MEMBER_UID)
    denied(circle(aliceUid).document("marc").set(mapOf("firstName" to "Marc")))
    denied(member.update("firstName", "Sophia"))
    denied(member.delete())
  }

  @Test
  fun otherUserCannotWrite(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(circle(aliceUid).document("bob").set(mapOf("firstName" to "Bob")))
  }

  private companion object {
    const val MEMBER_UID = "sophie"
  }
}
