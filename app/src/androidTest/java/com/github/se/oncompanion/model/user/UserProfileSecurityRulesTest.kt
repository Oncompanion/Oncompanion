package com.github.se.oncompanion.model.user

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
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

/** Tests firestore.rules for /users/{uid} through the client SDK against the emulator. */
@RunWith(AndroidJUnit4::class)
class UserProfileSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    bobUid = EmulatorTestData.createUser("bob")
    aliceUid = EmulatorTestData.createUser("alice") // alice stays signed in
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  private fun userDoc(uid: String): DocumentReference = db.collection("users").document(uid)

  private fun validProfile(): MutableMap<String, Any?> =
      mutableMapOf(
          "role" to "PATIENT",
          "firstName" to "Alice",
          "familyName" to "Martin",
          "cancerType" to "Breast cancer",
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  private suspend fun createAliceProfile() {
    allowed(userDoc(aliceUid).set(validProfile()))
  }

  // ---- read / delete ----

  @Test
  fun ownerCanCreateReadAndDelete(): Unit = runBlocking {
    createAliceProfile()
    val snapshot = allowed(userDoc(aliceUid).get())
    assertTrue(snapshot.exists())
    allowed(userDoc(aliceUid).delete())
  }

  @Test
  fun ownerCanCreateWithOnlyRequiredFields(): Unit = runBlocking {
    allowed(
        userDoc(aliceUid)
            .set(
                mapOf(
                    "role" to "CAREGIVER",
                    "firstName" to "Al",
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            )
    )
  }

  @Test
  fun ownerCanCreateWithNullOptionals(): Unit = runBlocking {
    allowed(
        userDoc(aliceUid)
            .set(
                validProfile().apply {
                  put("familyName", null)
                  put("cancerType", null)
                }
            )
    )
  }

  @Test
  fun ownerCanCreateWithMaxLengths(): Unit = runBlocking {
    allowed(
        userDoc(aliceUid)
            .set(
                validProfile().apply {
                  put("firstName", "a".repeat(50))
                  put("familyName", "b".repeat(50))
                  put("cancerType", "c".repeat(100))
                }
            )
    )
  }

  @Test
  fun otherUserCannotReadProfile(): Unit = runBlocking {
    createAliceProfile()
    EmulatorTestData.signIn("bob")
    denied(userDoc(aliceUid).get(Source.SERVER))
  }

  @Test
  fun signedOutCannotReadProfile(): Unit = runBlocking {
    createAliceProfile()
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(userDoc(aliceUid).get(Source.SERVER))
  }

  @Test
  fun otherUserCannotDeleteProfile(): Unit = runBlocking {
    createAliceProfile()
    EmulatorTestData.signIn("bob")
    denied(userDoc(aliceUid).delete())
  }

  // ---- create ----

  @Test
  fun otherUserCannotCreateProfile(): Unit = runBlocking {
    denied(userDoc(bobUid).set(validProfile()))
  }

  @Test
  fun signedOutCannotCreateProfile(): Unit = runBlocking {
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(userDoc(aliceUid).set(validProfile()))
  }

  @Test
  fun createWithExtraFieldIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("isAdmin", true) }))
  }

  @Test
  fun createWithoutFirstNameIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { remove("firstName") }))
  }

  @Test
  fun createWithoutRoleIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { remove("role") }))
  }

  @Test
  fun createWithoutCreatedAtIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { remove("createdAt") }))
  }

  @Test
  fun createWithBlankFirstNameIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("firstName", "   ") }))
  }

  @Test
  fun createWithNonStringFirstNameIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("firstName", 42) }))
  }

  @Test
  fun createWithTooLongFirstNameIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("firstName", "a".repeat(51)) }))
  }

  @Test
  fun createWithTooLongFamilyNameIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("familyName", "b".repeat(51)) }))
  }

  @Test
  fun createWithTooLongCancerTypeIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("cancerType", "c".repeat(101)) }))
  }

  @Test
  fun createWithInvalidRoleIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("role", "ADMIN") }))
  }

  @Test
  fun createWithClientChosenCreatedAtIsDenied(): Unit = runBlocking {
    denied(userDoc(aliceUid).set(validProfile().apply { put("createdAt", Timestamp.now()) }))
  }

  // ---- update ----

  @Test
  fun ownerCanUpdateFields(): Unit = runBlocking {
    createAliceProfile()
    allowed(
        userDoc(aliceUid)
            .update(
                mapOf(
                    "firstName" to "Alicia",
                    "familyName" to null,
                    "cancerType" to "Lymphoma",
                )
            )
    )
    val stored = allowed(userDoc(aliceUid).get(Source.SERVER))
    assertEquals("Alicia", stored.getString("firstName"))
    assertEquals("PATIENT", stored.getString("role"))
  }

  @Test
  fun ownerUpdateKeepingSameRoleIsAllowed(): Unit = runBlocking {
    createAliceProfile()
    allowed(userDoc(aliceUid).update(mapOf("role" to "PATIENT", "firstName" to "Alicia")))
  }

  @Test
  fun ownerUpdateChangingRoleFromPatientToCaregiverIsDenied(): Unit = runBlocking {
    createAliceProfile()
    denied(userDoc(aliceUid).update("role", "CAREGIVER"))
    denied(userDoc(aliceUid).update(mapOf("role" to "CAREGIVER", "firstName" to "Alicia")))
    assertEquals("PATIENT", allowed(userDoc(aliceUid).get(Source.SERVER)).getString("role"))
  }

  @Test
  fun ownerUpdateChangingRoleFromCaregiverToPatientIsDenied(): Unit = runBlocking {
    allowed(userDoc(aliceUid).set(validProfile().apply { put("role", "CAREGIVER") }))
    denied(userDoc(aliceUid).update("role", "PATIENT"))
    assertEquals("CAREGIVER", allowed(userDoc(aliceUid).get(Source.SERVER)).getString("role"))
  }

  @Test
  fun ownerUpdateRemovingRoleIsDenied(): Unit = runBlocking {
    createAliceProfile()
    denied(userDoc(aliceUid).update("role", FieldValue.delete()))
  }

  @Test
  fun ownerOverwriteWithDifferentRoleIsDenied(): Unit = runBlocking {
    createAliceProfile()
    val createdAt = allowed(userDoc(aliceUid).get(Source.SERVER)).getTimestamp("createdAt")
    denied(
        userDoc(aliceUid)
            .set(
                validProfile().apply {
                  put("role", "CAREGIVER")
                  put("createdAt", createdAt)
                }
            )
    )
  }

  @Test
  fun updateChangingCreatedAtIsDenied(): Unit = runBlocking {
    createAliceProfile()
    denied(userDoc(aliceUid).update("createdAt", Timestamp(0, 0)))
  }

  @Test
  fun updateResettingCreatedAtToServerTimeIsDenied(): Unit = runBlocking {
    createAliceProfile()
    denied(userDoc(aliceUid).update("createdAt", FieldValue.serverTimestamp()))
  }

  @Test
  fun updateWithInvalidDataIsDenied(): Unit = runBlocking {
    createAliceProfile()
    denied(userDoc(aliceUid).update("role", "ADMIN"))
    denied(userDoc(aliceUid).update("firstName", ""))
    denied(userDoc(aliceUid).update("firstName", "a".repeat(51)))
    denied(userDoc(aliceUid).update("cancerType", "c".repeat(101)))
    denied(userDoc(aliceUid).set(mapOf("isAdmin" to true), SetOptions.merge()))
    denied(userDoc(aliceUid).update("firstName", FieldValue.delete()))
  }

  @Test
  fun otherUserCannotUpdateProfile(): Unit = runBlocking {
    createAliceProfile()
    EmulatorTestData.signIn("bob")
    denied(userDoc(aliceUid).update("firstName", "Hacked"))
  }

  // ---- subcollections and other paths ----

  @Test
  fun ownerCannotWriteOrReadAnUnmatchedSubcollection(): Unit = runBlocking {
    // No catch-all rule: a collection under the user without its own `match` is denied
    val doc = userDoc(aliceUid).collection("notes").document("s1")
    denied(doc.set(mapOf("text" to "Ask about fatigue")))
    denied(doc.get(Source.SERVER))
    denied(userDoc(aliceUid).collection("notes").get(Source.SERVER))
  }

  @Test
  fun randomTopLevelCollectionIsDenied(): Unit = runBlocking {
    denied(db.collection("random").document("doc").set(mapOf("a" to 1)))
    denied(db.collection("random").document("doc").get(Source.SERVER))
  }
}
