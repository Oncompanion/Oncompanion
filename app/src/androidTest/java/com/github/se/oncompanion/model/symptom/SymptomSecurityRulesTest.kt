// Co-authored-by: Claude Code <noreply@anthropic.com>

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
import java.time.Duration
import java.time.Instant
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
          "types" to listOf("FATIGUE"),
          "intensity" to 6,
          "occurredAt" to Timestamp.now(),
          "notes" to "After the walk",
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private fun validOther(): MutableMap<String, Any?> =
      validSymptom().apply {
        put("types", listOf("OTHER"))
        put("notes", "Hiccups")
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

  private fun assertCreateAllowed(data: Map<String, Any?>) = runBlocking {
    allowed(symptoms(aliceUid).document().set(data))
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
  fun ownerCanCreateEveryListedTypeAlone_withoutNotes() {
    for (type in SymptomType.entries.filter { it != SymptomType.OTHER }) {
      assertCreateAllowed(
          validSymptom().apply {
            put("types", listOf(type.name))
            remove("notes")
          }
      )
    }
  }

  @Test
  fun ownerCanCreateSeveralTypes() {
    assertCreateAllowed(validSymptom().apply { put("types", listOf("FEVER", "HEADACHE")) })
    assertCreateAllowed(validOther().apply { put("types", SymptomType.entries.map { it.name }) })
  }

  @Test
  fun ownerCanCreateWithOnlyRequiredFields() {
    assertCreateAllowed(
        mapOf(
            "types" to listOf("PAIN"),
            "occurredAt" to Timestamp.now(),
            "createdAt" to FieldValue.serverTimestamp(),
        )
    )
  }

  @Test
  fun intensityAndNotesCanBeNull() {
    assertCreateAllowed(
        validSymptom().apply {
          put("intensity", null)
          put("notes", null)
        }
    )
  }

  @Test
  fun ownerCanCreateWithBoundaryValues() {
    assertCreateAllowed(validSymptom().apply { put("intensity", 1) })
    assertCreateAllowed(validSymptom().apply { put("intensity", 10) })
    assertCreateAllowed(validOther().apply { put("notes", "n".repeat(1000)) })
  }

  @Test
  fun otherWithNotesIsAllowed_aloneOrWithOtherSymptoms() {
    assertCreateAllowed(validOther())
    assertCreateAllowed(validOther().apply { put("types", listOf("FATIGUE", "OTHER")) })
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
    assertCreateDenied(validSymptom().apply { remove("types") })
    assertCreateDenied(validSymptom().apply { remove("occurredAt") })
    assertCreateDenied(validSymptom().apply { remove("createdAt") })
  }

  @Test
  fun createWithTheOldSingleTypeFieldsIsDenied() {
    assertCreateDenied(validSymptom().apply { put("type", "FATIGUE") })
    assertCreateDenied(validOther().apply { put("otherLabel", "Hiccups") })
    assertCreateDenied(
        validSymptom().apply {
          remove("types")
          put("type", "FATIGUE")
        }
    )
  }

  @Test
  fun createWithoutAnySymptomIsDenied() {
    assertCreateDenied(validSymptom().apply { put("types", emptyList<String>()) })
  }

  @Test
  fun createWithTheSameSymptomTwiceIsDenied() {
    assertCreateDenied(validSymptom().apply { put("types", listOf("PAIN", "PAIN")) })
  }

  @Test
  fun createWithInvalidTypesIsDenied() {
    assertCreateDenied(validSymptom().apply { put("types", listOf("fatigue")) })
    assertCreateDenied(validSymptom().apply { put("types", listOf("FATIGUE", "COUGH")) })
    assertCreateDenied(validSymptom().apply { put("types", listOf(1)) })
    assertCreateDenied(validSymptom().apply { put("types", "FATIGUE") })
  }

  @Test
  fun createWithInvalidIntensityIsDenied() {
    assertCreateDenied(validSymptom().apply { put("intensity", 0) })
    assertCreateDenied(validSymptom().apply { put("intensity", 11) })
    assertCreateDenied(validSymptom().apply { put("intensity", 5.5) })
    assertCreateDenied(validSymptom().apply { put("intensity", "5") })
  }

  @Test
  fun otherWithoutNotesIsDenied() {
    assertCreateDenied(validOther().apply { remove("notes") })
    assertCreateDenied(validOther().apply { put("notes", null) })
    assertCreateDenied(validOther().apply { put("notes", "") })
    assertCreateDenied(validOther().apply { put("notes", "  ") })
    assertCreateDenied(validOther().apply { put("notes", 42) })
  }

  @Test
  fun otherAmongSeveralSymptomsWithoutNotesIsDenied() {
    assertCreateDenied(
        validOther().apply {
          put("types", listOf("FATIGUE", "OTHER"))
          remove("notes")
        }
    )
  }

  @Test
  fun createInTheFutureIsDenied() {
    // More than the 5 minutes allowed for clock drift, measured against the server's time
    val inOneHour = Timestamp(Instant.now().plus(Duration.ofHours(1)))
    assertCreateDenied(validSymptom().apply { put("occurredAt", inOneHour) })
  }

  @Test
  fun createInThePastIsAllowed() {
    val lastYear = Timestamp(Instant.now().minus(Duration.ofDays(365)))
    assertCreateAllowed(validSymptom().apply { put("occurredAt", lastYear) })
  }

  @Test
  fun createWithInvalidNotesOrDatesIsDenied() {
    assertCreateDenied(validSymptom().apply { put("notes", "n".repeat(1001)) })
    assertCreateDenied(validOther().apply { put("notes", "n".repeat(1001)) })
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
  fun createWithUnrelatedDataIsDenied() {
    // No other rule allows writes under the user, whatever the data
    assertCreateDenied(mapOf("anything" to true))
  }
}
