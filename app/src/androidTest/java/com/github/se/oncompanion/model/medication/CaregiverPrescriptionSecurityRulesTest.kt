// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that firestore.rules let a caregiver add a prescription for a patient only when the patient
 * shares their prescriptions and lets them add (US-43): waiting for the patient, signed with their
 * own uid, and never confirmed, edited or deleted by them. Only the patient confirms or rejects it.
 * alice is the patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverPrescriptionSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String
  private lateinit var daveUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    daveUid = EmulatorTestData.createUser("presc-dave")
    aliceUid = EmulatorTestData.createUser("presc-alice")
    carolUid = EmulatorTestData.createUser("presc-carol") // carol stays signed in
    shareWithCarol(see = listOf("PRESCRIPTIONS"), add = listOf("PRESCRIPTIONS"))
  }

  @After fun tearDown(): Unit = runBlocking { EmulatorTestData.signOut() }

  /** Alice changes what carol sees and may add to (from another device, bypassing the rules). */
  private suspend fun shareWithCarol(see: List<String>, add: List<String>) {
    fun list(names: List<String>) =
        names.joinToString(", ", "[", "]") { """{"stringValue": "$it"}""" }
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": ${list(see)}}},
            "canAdd": {"arrayValue": {"values": ${list(add)}}},
            "inviteCode": {"stringValue": "ABCDEFGHJK"}}""",
    )
  }

  private fun prescriptions(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(MedicationRepositoryFirestore.PRESCRIPTIONS)

  /** A valid new prescription of the patient's own, with [extra] fields (null removes one). */
  private fun prescription(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      (mapOf(
              "prescribedOn" to Timestamp.now(),
              "createdAt" to FieldValue.serverTimestamp(),
              "medications" to listOf(mapOf("id" to "m1", "name" to "Ondansetron 8 mg")),
          ) + extra)
          .filterValues { it != null }

  /** A prescription carol adds for alice, waiting for her, with [extra] fields. */
  private fun carolsPrescription(vararg extra: Pair<String, Any?>) =
      prescription(
          "addedBy" to carolUid,
          "addedByName" to "Carol",
          "confirmation" to "PENDING",
          *extra,
      )

  @Test
  fun caregiverAllowedToAdd_addsOneWaitingForThePatient_signedWithTheirUid(): Unit = runBlocking {
    allowed(prescriptions(aliceUid).document().set(carolsPrescription()))
  }

  @Test
  fun caregiverWithoutCanAdd_orNotSeeingPrescriptions_cantAdd(): Unit = runBlocking {
    shareWithCarol(see = listOf("PRESCRIPTIONS"), add = emptyList())
    denied(prescriptions(aliceUid).document().set(carolsPrescription()))

    // canAdd alone isn't enough: the member must also see the section
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PRESCRIPTIONS"))
    denied(prescriptions(aliceUid).document().set(carolsPrescription()))
  }

  @Test
  fun caregiver_cantAddOneAlreadyConfirmed_orRejected(): Unit = runBlocking {
    for (state in listOf("CONFIRMED", "REJECTED", null, "MAYBE")) {
      denied(prescriptions(aliceUid).document().set(carolsPrescription("confirmation" to state)))
    }
  }

  @Test
  fun caregiver_cantSignInSomeoneElsesName_orLeaveTheNameOut(): Unit = runBlocking {
    val doc = prescriptions(aliceUid)
    denied(doc.document().set(carolsPrescription("addedBy" to aliceUid)))
    denied(doc.document().set(carolsPrescription("addedBy" to null)))
    denied(doc.document().set(carolsPrescription("addedByName" to null)))
    denied(doc.document().set(carolsPrescription("addedByName" to "  ")))
    denied(doc.document().set(carolsPrescription("addedByName" to "a".repeat(51))))
  }

  @Test
  fun caregiver_followsThePrescriptionValidation(): Unit = runBlocking {
    denied(
        prescriptions(aliceUid)
            .document()
            .set(carolsPrescription("medications" to emptyList<Any>()))
    )
    denied(
        prescriptions(aliceUid)
            .document()
            .set(carolsPrescription("prescribedBy" to "a".repeat(101)))
    )
  }

  @Test
  fun removedCaregiver_orStranger_cantAdd(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(prescriptions(aliceUid).document().set(carolsPrescription()))

    EmulatorTestData.signIn("presc-dave")
    denied(
        prescriptions(aliceUid)
            .document()
            .set(carolsPrescription("addedBy" to daveUid, "addedByName" to "Dave"))
    )
  }

  @Test
  fun caregiver_cantConfirmEditOrDelete_evenTheirOwnPrescription(): Unit = runBlocking {
    val doc = prescriptions(aliceUid).document()
    allowed(doc.set(carolsPrescription()))

    denied(doc.update("confirmation", "CONFIRMED"))
    denied(doc.update("prescribedBy", "Dr. Martin"))
    denied(doc.delete())
  }

  @Test
  fun patient_confirmsOrRejects_onlyOnce_andCantEditWithoutConfirming(): Unit = runBlocking {
    val confirmed = prescriptions(aliceUid).document()
    val rejected = prescriptions(aliceUid).document()
    allowed(confirmed.set(carolsPrescription()))
    allowed(rejected.set(carolsPrescription()))
    EmulatorTestData.signIn("presc-alice")

    // Editing while it waits for her isn't possible: saving confirms it
    denied(confirmed.update("prescribedBy", "Dr. Martin"))
    allowed(confirmed.update(mapOf("prescribedBy" to "Dr. Martin", "confirmation" to "CONFIRMED")))
    allowed(rejected.update("confirmation", "REJECTED"))

    // A decided prescription keeps its state, and who added it never changes
    denied(confirmed.update("confirmation", "PENDING"))
    denied(confirmed.update("confirmation", "REJECTED"))
    denied(rejected.update("confirmation", "CONFIRMED"))
    allowed(confirmed.update("prescribedBy", "Dr. Rossi"))
    denied(confirmed.update("addedByName", "Bob"))
    denied(confirmed.update("addedBy", FieldValue.delete()))
  }

  @Test
  fun patient_addsTheirOwnAsBefore_confirmed_andUnsigned(): Unit = runBlocking {
    EmulatorTestData.signIn("presc-alice")
    val doc = prescriptions(aliceUid)

    // Saved before US-43 without the field, or with it
    allowed(doc.document().set(prescription()))
    allowed(doc.document().set(prescription("confirmation" to "CONFIRMED")))
    denied(doc.document().set(prescription("confirmation" to "PENDING")))
    denied(doc.document().set(prescription("addedBy" to aliceUid, "addedByName" to "Alice")))
  }
}
