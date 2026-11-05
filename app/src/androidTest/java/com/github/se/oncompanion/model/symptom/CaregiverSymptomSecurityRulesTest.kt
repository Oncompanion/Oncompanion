// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.symptom

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
 * Tests that firestore.rules let a caregiver log a symptom in a patient's journal only when the
 * patient shares their symptoms and lets them add (US-22), signed with their own uid, and never
 * edit or delete one. alice is the patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverSymptomSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String
  private lateinit var daveUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    daveUid = EmulatorTestData.createUser("symptom-dave")
    aliceUid = EmulatorTestData.createUser("symptom-alice")
    carolUid = EmulatorTestData.createUser("symptom-carol") // carol stays signed in
    shareWithCarol(see = listOf("SYMPTOMS"), add = listOf("SYMPTOMS"))
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

  private fun journal(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(SymptomRepositoryFirestore.COLLECTION)

  /** A valid new symptom, with [extra] fields (null removes one). */
  private fun symptom(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      (mapOf(
              "types" to listOf("NAUSEA"),
              "occurredAt" to Timestamp.now(),
              "createdAt" to FieldValue.serverTimestamp(),
          ) + extra)
          .filterValues { it != null }

  /** A symptom signed by the signed-in caregiver, with [extra] fields. */
  private fun carolsSymptom(vararg extra: Pair<String, Any?>) =
      symptom("addedBy" to carolUid, "addedByName" to "Carol", *extra)

  @Test
  fun caregiverAllowedToAdd_logsASymptomSignedWithTheirUid(): Unit = runBlocking {
    allowed(journal(aliceUid).document().set(carolsSymptom()))
  }

  @Test
  fun caregiverWithoutCanAdd_orNotSeeingSymptoms_cantAdd(): Unit = runBlocking {
    shareWithCarol(see = listOf("SYMPTOMS"), add = emptyList())
    denied(journal(aliceUid).document().set(carolsSymptom()))

    // canAdd alone isn't enough: the member must also see the section
    shareWithCarol(see = listOf("PLANNING"), add = listOf("SYMPTOMS"))
    denied(journal(aliceUid).document().set(carolsSymptom()))
  }

  @Test
  fun caregiver_cantSignInSomeoneElsesName_orLeaveTheNameOut(): Unit = runBlocking {
    denied(journal(aliceUid).document().set(carolsSymptom("addedBy" to aliceUid)))
    denied(journal(aliceUid).document().set(carolsSymptom("addedBy" to null)))
    denied(journal(aliceUid).document().set(carolsSymptom("addedByName" to null)))
    denied(journal(aliceUid).document().set(carolsSymptom("addedByName" to "  ")))
    denied(journal(aliceUid).document().set(carolsSymptom("addedByName" to "a".repeat(51))))
  }

  @Test
  fun caregiver_followsTheSymptomValidation(): Unit = runBlocking {
    denied(journal(aliceUid).document().set(carolsSymptom("types" to emptyList<String>())))
    denied(journal(aliceUid).document().set(carolsSymptom("notes" to "a".repeat(1001))))
  }

  @Test
  fun removedCaregiver_orStranger_cantAdd(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(journal(aliceUid).document().set(carolsSymptom()))

    EmulatorTestData.signIn("symptom-dave")
    denied(journal(aliceUid).document().set(symptom("addedBy" to daveUid, "addedByName" to "Dave")))
  }

  @Test
  fun caregiver_cantEditOrDelete_evenTheirOwnEntry(): Unit = runBlocking {
    val entry = journal(aliceUid).document()
    allowed(entry.set(carolsSymptom()))

    denied(entry.update("notes", "Changed"))
    denied(entry.delete())
  }

  @Test
  fun patient_cantSignTheirOwnEntry_butKeepsLoggingAsBefore(): Unit = runBlocking {
    EmulatorTestData.signIn("symptom-alice")

    allowed(journal(aliceUid).document().set(symptom()))
    denied(
        journal(aliceUid).document().set(symptom("addedBy" to aliceUid, "addedByName" to "Alice"))
    )
  }

  @Test
  fun patient_correctsACaregiversEntry_withoutChangingWhoAddedIt(): Unit = runBlocking {
    val entry = journal(aliceUid).document()
    allowed(entry.set(carolsSymptom()))
    EmulatorTestData.signIn("symptom-alice")

    allowed(entry.update("notes", "After lunch"))
    denied(entry.update("addedByName", "Bob"))
    denied(entry.update("addedBy", FieldValue.delete()))
    allowed(entry.delete())
  }
}
