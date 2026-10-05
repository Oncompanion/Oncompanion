package com.github.se.oncompanion.model.symptom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
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

/** Tests firestore.rules for /users/{uid}/symptoms/{id} through the client SDK. */
@RunWith(AndroidJUnit4::class)
class SymptomSecurityRulesTest {

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

  private fun symptoms(uid: String): CollectionReference =
      db.collection("users").document(uid).collection("symptoms")

  private fun validSymptom(): MutableMap<String, Any?> =
      mutableMapOf(
          "type" to "FATIGUE",
          "otherLabel" to null,
          "intensity" to 6,
          "occurredAt" to Timestamp.now(),
          "notes" to "After the walk",
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private fun validOther(): MutableMap<String, Any?> =
      validSymptom().apply {
        put("type", "OTHER")
        put("otherLabel", "Hiccups")
      }

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  private fun assertCreateDenied(data: Map<String, Any?>) = runBlocking {
    denied(symptoms(aliceUid).document().set(data))
  }

  // ---- read / create ----

  @Test
  fun ownerCanCreateAndRead(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    assertTrue(allowed(doc.get(Source.SERVER)).exists())
    assertEquals(1, allowed(symptoms(aliceUid).get(Source.SERVER)).size())
  }

  @Test
  fun ownerCanCreateEveryType(): Unit = runBlocking {
    for (type in SymptomType.entries.filter { it != SymptomType.OTHER }) {
      allowed(symptoms(aliceUid).document().set(validSymptom().apply { put("type", type.name) }))
    }
    allowed(symptoms(aliceUid).document().set(validOther()))
  }

  @Test
  fun ownerCanCreateWithOnlyRequiredFields(): Unit = runBlocking {
    allowed(
        symptoms(aliceUid)
            .document()
            .set(
                mapOf(
                    "type" to "PAIN",
                    "intensity" to 1,
                    "occurredAt" to Timestamp.now(),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
            )
    )
  }

  @Test
  fun ownerCanCreateWithBoundaryValues(): Unit = runBlocking {
    allowed(symptoms(aliceUid).document().set(validSymptom().apply { put("intensity", 10) }))
    allowed(
        symptoms(aliceUid)
            .document()
            .set(
                validOther().apply {
                  put("otherLabel", "a".repeat(50))
                  put("notes", "n".repeat(1000))
                }
            )
    )
  }

  @Test
  fun otherUserCannotReadOrCreate(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    EmulatorTestData.signIn("bob")
    denied(doc.get(Source.SERVER))
    denied(symptoms(aliceUid).get(Source.SERVER))
    denied(symptoms(aliceUid).document().set(validSymptom()))
  }

  @Test
  fun signedOutCannotCreate(): Unit = runBlocking {
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(symptoms(aliceUid).document().set(validSymptom()))
  }

  @Test
  fun cannotCreateForAnotherUser(): Unit = runBlocking {
    denied(symptoms(bobUid).document().set(validSymptom()))
  }

  // ---- validation ----

  @Test
  fun createWithMissingOrExtraFieldsIsDenied() {
    assertCreateDenied(validSymptom().apply { put("isUrgent", true) })
    assertCreateDenied(validSymptom().apply { remove("type") })
    assertCreateDenied(validSymptom().apply { remove("intensity") })
    assertCreateDenied(validSymptom().apply { remove("occurredAt") })
    assertCreateDenied(validSymptom().apply { remove("createdAt") })
  }

  @Test
  fun createWithInvalidTypeIsDenied() {
    assertCreateDenied(validSymptom().apply { put("type", "fatigue") })
    assertCreateDenied(validSymptom().apply { put("type", "COUGH") })
    assertCreateDenied(validSymptom().apply { put("type", 1) })
  }

  @Test
  fun createWithInvalidIntensityIsDenied() {
    assertCreateDenied(validSymptom().apply { put("intensity", 0) })
    assertCreateDenied(validSymptom().apply { put("intensity", 11) })
    assertCreateDenied(validSymptom().apply { put("intensity", 5.5) })
    assertCreateDenied(validSymptom().apply { put("intensity", "5") })
  }

  @Test
  fun otherWithoutValidLabelIsDenied() {
    assertCreateDenied(validOther().apply { remove("otherLabel") })
    assertCreateDenied(validOther().apply { put("otherLabel", null) })
    assertCreateDenied(validOther().apply { put("otherLabel", "  ") })
    assertCreateDenied(validOther().apply { put("otherLabel", "a".repeat(51)) })
    assertCreateDenied(validOther().apply { put("otherLabel", 42) })
  }

  @Test
  fun listedTypeWithLabelIsDenied() {
    assertCreateDenied(validSymptom().apply { put("otherLabel", "Hiccups") })
  }

  @Test
  fun createWithInvalidNotesOrDatesIsDenied() {
    assertCreateDenied(validSymptom().apply { put("notes", "n".repeat(1001)) })
    assertCreateDenied(validSymptom().apply { put("notes", 42) })
    assertCreateDenied(validSymptom().apply { put("occurredAt", "2026-10-05") })
    assertCreateDenied(validSymptom().apply { put("createdAt", Timestamp.now()) })
  }

  // ---- entries can't change yet ----

  @Test
  fun ownerCannotUpdateOrDelete(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    denied(doc.update("intensity", 2))
    denied(doc.set(mapOf("intensity" to 2), SetOptions.merge()))
    denied(doc.delete())
  }

  @Test
  fun createWithUnrelatedDataIsDenied(): Unit = runBlocking {
    // No other rule allows writes under the user, whatever the data
    assertCreateDenied(mapOf("anything" to true))
  }
}
