// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

  // ---- delete ----

  @Test
  fun ownerCanDelete(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    allowed(doc.delete())
    assertFalse(allowed(doc.get(Source.SERVER)).exists())
  }

  @Test
  fun otherUserCannotDelete(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    EmulatorTestData.signIn("bob")
    denied(doc.delete())
  }

  @Test
  fun signedOutCannotDelete(): Unit = runBlocking {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(validSymptom()))
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(doc.delete())
  }

  // ---- update ----

  /** Saves a valid symptom of alice and returns it, to be edited. */
  private suspend fun savedSymptom(data: Map<String, Any?> = validSymptom()): DocumentReference {
    val doc = symptoms(aliceUid).document()
    allowed(doc.set(data))
    return doc
  }

  private fun assertUpdateAllowed(changes: Map<String, Any?>) = runBlocking {
    allowed(savedSymptom().update(changes))
  }

  private fun assertUpdateDenied(changes: Map<String, Any?>) = runBlocking {
    denied(savedSymptom().update(changes))
  }

  @Test
  fun ownerCanUpdateEveryField(): Unit = runBlocking {
    val doc = savedSymptom()
    val yesterday = Timestamp(Instant.now().minus(Duration.ofDays(1)))
    allowed(
        doc.update(
            mapOf(
                "types" to listOf("PAIN", "FEVER"),
                "intensity" to 3,
                "occurredAt" to yesterday,
                "notes" to "Worse in the evening",
            )
        )
    )
    val saved = allowed(doc.get(Source.SERVER))
    assertEquals(listOf("PAIN", "FEVER"), saved.get("types"))
    assertEquals(3L, saved.getLong("intensity"))
    assertEquals(yesterday, saved.getTimestamp("occurredAt"))
    assertEquals("Worse in the evening", saved.getString("notes"))
  }

  @Test
  fun ownerCanClearIntensityAndNotes() {
    assertUpdateAllowed(mapOf("intensity" to null, "notes" to null))
  }

  @Test
  fun ownerCanTickOtherWhenNotesAreAlreadyFilled() {
    // The notes saved with the entry are enough to say which symptom it was
    assertUpdateAllowed(mapOf("types" to listOf("FATIGUE", "OTHER")))
  }

  @Test
  fun ownerCanMoveTheSymptomAfterItWasLogged(): Unit = runBlocking {
    // Later than createdAt is fine as long as it isn't in the future
    val doc = savedSymptom()
    val createdAt = allowed(doc.get(Source.SERVER)).getTimestamp("createdAt")!!
    allowed(doc.update("occurredAt", Timestamp(createdAt.toInstant().plusMillis(1))))
  }

  @Test
  fun updateInTheFutureIsDenied() {
    val inOneHour = Timestamp(Instant.now().plus(Duration.ofHours(1)))
    assertUpdateDenied(mapOf("occurredAt" to inOneHour))
  }

  @Test
  fun updateChangingCreatedAtIsDenied() {
    assertUpdateDenied(mapOf("createdAt" to Timestamp.now()))
    assertUpdateDenied(mapOf("createdAt" to FieldValue.serverTimestamp()))
    assertUpdateDenied(mapOf("createdAt" to FieldValue.delete()))
  }

  @Test
  fun updateWithInvalidDataIsDenied() {
    assertUpdateDenied(mapOf("types" to emptyList<String>()))
    assertUpdateDenied(mapOf("types" to listOf("PAIN", "PAIN")))
    assertUpdateDenied(mapOf("intensity" to 11))
    assertUpdateDenied(mapOf("notes" to "n".repeat(1001)))
    assertUpdateDenied(mapOf("isUrgent" to true))
    assertUpdateDenied(mapOf("types" to FieldValue.delete()))
  }

  @Test
  fun tickingOtherWithoutNotesIsDenied() {
    assertUpdateDenied(mapOf("types" to listOf("OTHER"), "notes" to null))
    assertUpdateDenied(mapOf("types" to listOf("FATIGUE", "OTHER"), "notes" to "  "))
  }

  @Test
  fun clearingTheNotesOfOtherIsDenied(): Unit = runBlocking {
    denied(savedSymptom(validOther()).update("notes", null))
  }

  @Test
  fun otherUserCannotUpdate(): Unit = runBlocking {
    val doc = savedSymptom()
    EmulatorTestData.signIn("bob")
    denied(doc.update("intensity", 2))
    denied(doc.set(mapOf("intensity" to 2), SetOptions.merge()))
  }

  @Test
  fun signedOutCannotUpdate(): Unit = runBlocking {
    val doc = savedSymptom()
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(doc.update("intensity", 2))
  }

  @Test
  fun createWithUnrelatedDataIsDenied() {
    // No other rule allows writes under the user, whatever the data
    assertCreateDenied(mapOf("anything" to true))
  }
}
