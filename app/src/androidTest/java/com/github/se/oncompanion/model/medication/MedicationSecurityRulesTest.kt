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
 * Tests firestore.rules for /users/{uid}/prescriptions through the client SDK against the emulator.
 * A prescription document holds its medications; there is no medications collection.
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

  private val someDay = Timestamp(Instant.parse("2026-09-28T22:00:00Z"))

  private fun validMedication(): Map<String, Any?> =
      mapOf(
          "id" to "med-1",
          "name" to "Ondansetron 8 mg",
          "dosage" to "1 tablet",
          "frequency" to "Twice a day",
          "startDate" to someDay,
          "durationDays" to 5,
      )

  private fun validPrescription(): MutableMap<String, Any?> =
      mutableMapOf(
          "prescribedBy" to "Dr. Martin · Oncology",
          "prescribedOn" to someDay,
          "createdAt" to FieldValue.serverTimestamp(),
          "medications" to listOf(validMedication()),
      )

  private fun prescriptionWith(key: String, value: Any?) =
      validPrescription().apply { put(key, value) }

  private fun prescriptionWithout(key: String) = validPrescription().apply { remove(key) }

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10.seconds) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10.seconds) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- owner ----

  @Test
  fun ownerCanCreateReadUpdateAndDeleteAPrescription(): Unit = runBlocking {
    val doc = prescriptions().document("presc-1")
    allowed(doc.set(validPrescription()))
    assertTrue(allowed(doc.get(Source.SERVER)).exists())
    allowed(
        doc.update(
            mapOf(
                "prescribedBy" to "Dr. Leroy",
                "prescribedOn" to Timestamp.now(),
                "medications" to listOf(validMedication(), validMedication()),
            )
        )
    )
    assertEquals(1, allowed(prescriptions().get(Source.SERVER)).size())
    allowed(doc.delete())
  }

  @Test
  fun ownerCanCreateAPrescriptionWithoutADoctor(): Unit = runBlocking {
    allowed(prescriptions().document("absent").set(prescriptionWithout("prescribedBy")))
    allowed(prescriptions().document("null").set(prescriptionWith("prescribedBy", null)))
  }

  @Test
  fun prescribedByIsLimitedTo100Characters(): Unit = runBlocking {
    allowed(prescriptions().document("max").set(prescriptionWith("prescribedBy", "a".repeat(100))))
    denied(prescriptions().document("long").set(prescriptionWith("prescribedBy", "a".repeat(101))))
    denied(prescriptions().document("number").set(prescriptionWith("prescribedBy", 3)))
  }

  @Test
  fun prescribedOnMustBeATimestamp(): Unit = runBlocking {
    denied(prescriptions().document("missing").set(prescriptionWithout("prescribedOn")))
    denied(prescriptions().document("text").set(prescriptionWith("prescribedOn", "2026-09-29")))
  }

  @Test
  fun prescriptionCreatedAtMustBeTheServerTime(): Unit = runBlocking {
    denied(prescriptions().document("missing").set(prescriptionWithout("createdAt")))
    denied(
        prescriptions()
            .document("client")
            .set(prescriptionWith("createdAt", Timestamp(Instant.EPOCH)))
    )
  }

  @Test
  fun prescriptionHasBetween1And20Medications(): Unit = runBlocking {
    allowed(
        prescriptions()
            .document("max")
            .set(prescriptionWith("medications", List(20) { validMedication() }))
    )
    denied(prescriptions().document("missing").set(prescriptionWithout("medications")))
    denied(
        prescriptions()
            .document("none")
            .set(prescriptionWith("medications", emptyList<Map<String, Any?>>()))
    )
    denied(
        prescriptions()
            .document("too-many")
            .set(prescriptionWith("medications", List(21) { validMedication() }))
    )
    denied(prescriptions().document("null").set(prescriptionWith("medications", null)))
    denied(prescriptions().document("text").set(prescriptionWith("medications", "Ondansetron")))
    denied(prescriptions().document("map").set(prescriptionWith("medications", validMedication())))
  }

  @Test
  fun prescriptionCannotHaveUnknownFields(): Unit = runBlocking {
    denied(prescriptions().document("extra").set(prescriptionWith("notes", "hello")))
  }

  @Test
  fun prescriptionUpdateIsValidatedAndCannotChangeCreatedAt(): Unit = runBlocking {
    val doc = prescriptions().document("presc-1")
    allowed(doc.set(validPrescription()))

    denied(doc.update(mapOf("createdAt" to Timestamp(Instant.EPOCH))))
    denied(doc.update(mapOf("createdAt" to FieldValue.serverTimestamp())))
    denied(doc.update(mapOf("prescribedBy" to "a".repeat(101))))
    denied(doc.update(mapOf("medications" to emptyList<Map<String, Any?>>())))
  }

  @Test
  fun medicationsAreNotStoredAsSeparateDocuments(): Unit = runBlocking {
    val medications = db.collection("users").document(aliceUid).collection("medications")

    denied(medications.document("med-1").set(validMedication()))
    denied(medications.document("med-1").get(Source.SERVER))
    denied(medications.get(Source.SERVER))
  }

  // ---- other users ----

  @Test
  fun otherUserCannotReadPrescriptions(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    EmulatorTestData.signIn("bob")

    denied(prescriptions().document("presc-1").get(Source.SERVER))
    denied(prescriptions().get(Source.SERVER))
  }

  @Test
  fun otherUserCannotWritePrescriptions(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    EmulatorTestData.signIn("bob")

    denied(prescriptions().document("by-bob").set(validPrescription()))
    denied(prescriptions().document("presc-1").update(mapOf("prescribedBy" to "Dr. Bob")))
    denied(prescriptions().document("presc-1").delete())
  }

  @Test
  fun signedOutCannotReadOrWrite(): Unit = runBlocking {
    allowed(prescriptions().document("presc-1").set(validPrescription()))
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)

    denied(prescriptions().document("presc-1").get(Source.SERVER))
    denied(prescriptions().document("new").set(validPrescription()))
  }
}
