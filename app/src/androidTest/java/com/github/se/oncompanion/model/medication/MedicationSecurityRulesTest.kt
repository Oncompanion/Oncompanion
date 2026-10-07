package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
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

/**
 * Tests firestore.rules for /users/{uid}/prescriptions and /users/{uid}/medications through the
 * client SDK against the emulator.
 */
@RunWith(AndroidJUnit4::class)
class MedicationSecurityRulesTest {

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

  private fun prescriptions(uid: String = aliceUid): CollectionReference =
      db.collection("users").document(uid).collection("prescriptions")

  private fun medications(uid: String = aliceUid): CollectionReference =
      db.collection("users").document(uid).collection("medications")

  private val someDay = Timestamp(Instant.parse("2026-09-28T22:00:00Z"))

  private fun validPrescription(): MutableMap<String, Any?> =
      mutableMapOf(
          "prescribedBy" to "Dr. Martin · Oncology",
          "prescribedOn" to someDay,
          "createdAt" to FieldValue.serverTimestamp(),
      )

  private fun validMedication(): MutableMap<String, Any?> =
      mutableMapOf(
          "prescriptionId" to "presc-1",
          "name" to "Ondansetron 8 mg",
          "dosage" to "1 tablet",
          "frequency" to "Twice a day",
          "startDate" to someDay,
          "durationDays" to 5,
          "position" to 0,
      )

  private fun medicationWith(key: String, value: Any?) = validMedication().apply { put(key, value) }

  private fun medicationWithout(key: String) = validMedication().apply { remove(key) }

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10.seconds) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10.seconds) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- prescriptions: owner ----

  @Test
  fun ownerCanCreateReadUpdateAndDeleteAPrescription(): Unit = runBlocking {
    val doc = prescriptions().document("presc-1")
    allowed(doc.set(validPrescription()))
    assertTrue(allowed(doc.get(Source.SERVER)).exists())
    allowed(doc.update(mapOf("prescribedBy" to "Dr. Leroy", "prescribedOn" to Timestamp.now())))
    assertEquals(1, allowed(prescriptions().get(Source.SERVER)).size())
    allowed(doc.delete())
  }

  @Test
  fun ownerCanCreateAPrescriptionWithoutADoctor(): Unit = runBlocking {
    allowed(
        prescriptions().document("absent").set(validPrescription().apply { remove("prescribedBy") })
    )
    allowed(
        prescriptions()
            .document("null")
            .set(validPrescription().apply { put("prescribedBy", null) })
    )
  }

  @Test
  fun prescribedByIsLimitedTo100Characters(): Unit = runBlocking {
    allowed(
        prescriptions()
            .document("max")
            .set(validPrescription().apply { put("prescribedBy", "a".repeat(100)) })
    )
    denied(
        prescriptions()
            .document("long")
            .set(validPrescription().apply { put("prescribedBy", "a".repeat(101)) })
    )
    denied(
        prescriptions().document("number").set(validPrescription().apply { put("prescribedBy", 3) })
    )
  }

  @Test
  fun prescribedOnMustBeATimestamp(): Unit = runBlocking {
    denied(
        prescriptions()
            .document("missing")
            .set(validPrescription().apply { remove("prescribedOn") })
    )
    denied(
        prescriptions()
            .document("text")
            .set(validPrescription().apply { put("prescribedOn", "2026-09-29") })
    )
  }

  @Test
  fun prescriptionCreatedAtMustBeTheServerTime(): Unit = runBlocking {
    denied(
        prescriptions().document("missing").set(validPrescription().apply { remove("createdAt") })
    )
    denied(
        prescriptions()
            .document("client")
            .set(validPrescription().apply { put("createdAt", Timestamp(Instant.EPOCH)) })
    )
  }

  @Test
  fun prescriptionCannotHaveUnknownFields(): Unit = runBlocking {
    denied(
        prescriptions().document("extra").set(validPrescription().apply { put("notes", "hello") })
    )
  }

  @Test
  fun prescriptionUpdateCannotChangeCreatedAt(): Unit = runBlocking {
    val doc = prescriptions().document("presc-1")
    allowed(doc.set(validPrescription()))

    denied(doc.update(mapOf("createdAt" to Timestamp(Instant.EPOCH))))
    denied(doc.update(mapOf("createdAt" to FieldValue.serverTimestamp())))
    denied(doc.update(mapOf("prescribedBy" to "a".repeat(101))))
  }

  // ---- medications: owner ----

  @Test
  fun ownerCanCreateReadUpdateAndDeleteAMedication(): Unit = runBlocking {
    val doc = medications().document("med-1")
    allowed(doc.set(validMedication()))
    assertTrue(allowed(doc.get(Source.SERVER)).exists())
    allowed(doc.set(medicationWith("name", "Ondansetron 4 mg")))
    assertEquals(
        1,
        allowed(medications().whereEqualTo("prescriptionId", "presc-1").get(Source.SERVER)).size(),
    )
    allowed(doc.delete())
  }

  @Test
  fun ownerCanCreateAMedicationWithOnlyTheRequiredFields(): Unit = runBlocking {
    allowed(
        medications()
            .document("absent")
            .set(
                mapOf(
                    "prescriptionId" to "presc-1",
                    "name" to "Paracetamol 1 g",
                    "startDate" to someDay,
                    "position" to 0,
                )
            )
    )
    allowed(
        medications()
            .document("null")
            .set(
                validMedication().apply {
                  put("dosage", null)
                  put("frequency", null)
                  put("durationDays", null)
                }
            )
    )
  }

  @Test
  fun medicationNameIsRequiredAndLimitedTo100Characters(): Unit = runBlocking {
    allowed(medications().document("max").set(medicationWith("name", "a".repeat(100))))
    denied(medications().document("long").set(medicationWith("name", "a".repeat(101))))
    denied(medications().document("empty").set(medicationWith("name", "")))
    denied(medications().document("blank").set(medicationWith("name", "   ")))
    denied(medications().document("missing").set(medicationWithout("name")))
    denied(medications().document("number").set(medicationWith("name", 8)))
  }

  @Test
  fun dosageAndFrequencyAreLimitedTo100Characters(): Unit = runBlocking {
    allowed(
        medications()
            .document("max")
            .set(
                validMedication().apply {
                  put("dosage", "a".repeat(100))
                  put("frequency", "b".repeat(100))
                }
            )
    )
    denied(medications().document("dosage").set(medicationWith("dosage", "a".repeat(101))))
    denied(medications().document("frequency").set(medicationWith("frequency", "a".repeat(101))))
  }

  @Test
  fun startDateMustBeATimestamp(): Unit = runBlocking {
    denied(medications().document("missing").set(medicationWithout("startDate")))
    denied(medications().document("text").set(medicationWith("startDate", "2026-09-29")))
  }

  @Test
  fun durationMustBeAWholeNumberOfDaysBetween1And3650(): Unit = runBlocking {
    allowed(medications().document("one").set(medicationWith("durationDays", 1)))
    allowed(medications().document("max").set(medicationWith("durationDays", 3650)))
    denied(medications().document("zero").set(medicationWith("durationDays", 0)))
    denied(medications().document("negative").set(medicationWith("durationDays", -5)))
    denied(medications().document("too-long").set(medicationWith("durationDays", 3651)))
    denied(medications().document("decimal").set(medicationWith("durationDays", 1.5)))
    denied(medications().document("text").set(medicationWith("durationDays", "5")))
  }

  @Test
  fun positionMustBeBetween0And19(): Unit = runBlocking {
    allowed(medications().document("last").set(medicationWith("position", 19)))
    denied(medications().document("missing").set(medicationWithout("position")))
    denied(medications().document("negative").set(medicationWith("position", -1)))
    denied(medications().document("too-many").set(medicationWith("position", 20)))
    denied(medications().document("text").set(medicationWith("position", "0")))
  }

  @Test
  fun medicationMustBelongToAPrescription(): Unit = runBlocking {
    denied(medications().document("missing").set(medicationWithout("prescriptionId")))
    denied(medications().document("blank").set(medicationWith("prescriptionId", " ")))
    denied(medications().document("number").set(medicationWith("prescriptionId", 1)))
  }

  @Test
  fun medicationCannotMoveToAnotherPrescription(): Unit = runBlocking {
    val doc = medications().document("med-1")
    allowed(doc.set(validMedication()))

    denied(doc.update(mapOf("prescriptionId" to "presc-2")))
    denied(doc.update(mapOf("name" to "")))
  }

  @Test
  fun medicationCannotHaveUnknownFields(): Unit = runBlocking {
    denied(medications().document("extra").set(medicationWith("notes", "hello")))
  }

  // ---- other users ----

  @Test
  fun otherUserCannotReadPrescriptionsOrMedications(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    allowed(medications().document("med-1").set(validMedication()))
    EmulatorTestData.signIn("bob")

    denied(prescriptions().document("presc-1").get(Source.SERVER))
    denied(prescriptions().get(Source.SERVER))
    denied(medications().document("med-1").get(Source.SERVER))
    denied(medications().get(Source.SERVER))
  }

  @Test
  fun otherUserCannotWritePrescriptionsOrMedications(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    allowed(medications().document("med-1").set(validMedication()))
    EmulatorTestData.signIn("bob")

    denied(prescriptions().document("by-bob").set(validPrescription()))
    denied(prescriptions().document("presc-1").update(mapOf("prescribedBy" to "Dr. Bob")))
    denied(prescriptions().document("presc-1").delete())
    denied(medications().document("by-bob").set(validMedication()))
    denied(medications().document("med-1").update(mapOf("name" to "Something else")))
    denied(medications().document("med-1").delete())
  }

  @Test
  fun signedOutCannotReadOrWrite(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    allowed(medications().document("med-1").set(validMedication()))
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)

    denied(prescriptions().document("presc-1").get(Source.SERVER))
    denied(medications().document("med-1").get(Source.SERVER))
    denied(prescriptions().document("new").set(validPrescription()))
    denied(medications().document("new").set(validMedication()))
  }
}
