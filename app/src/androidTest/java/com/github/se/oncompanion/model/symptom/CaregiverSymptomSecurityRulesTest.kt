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
 * patient shares their symptoms and lets them add (US-22), signed with their own uid, and edit or
 * delete one only when the patient also lets them edit and delete there (US-46), signing their
 * edit. alice is the patient, carol her caregiver, dave a stranger.
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

  /**
   * Alice changes what carol sees, may add to, and may edit and delete in (from another device,
   * bypassing the rules).
   */
  private suspend fun shareWithCarol(
      see: List<String>,
      add: List<String>,
      edit: List<String> = emptyList(),
  ) {
    fun list(names: List<String>) =
        names.joinToString(", ", "[", "]") { """{"stringValue": "$it"}""" }
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": ${list(see)}}},
            "canAdd": {"arrayValue": {"values": ${list(add)}}},
            "canEditAndDelete": {"arrayValue": {"values": ${list(edit)}}},
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

  /** A symptom alice logged herself, before carol signs in again. */
  private suspend fun alicesSymptom() =
      journal(aliceUid).document().also {
        EmulatorTestData.signIn("symptom-alice")
        allowed(it.set(symptom()))
        EmulatorTestData.signIn("symptom-carol")
      }

  /** Carol's edit of a symptom, signed with her uid, with [extra] fields (null removes one). */
  private fun carolsEdit(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      mapOf("intensity" to 6, "editedBy" to carolUid, "editedByName" to "Carol") +
          extra.map { (key, value) -> key to (value ?: FieldValue.delete()) }

  private suspend fun letCarolEditSymptoms() =
      shareWithCarol(see = listOf("SYMPTOMS"), add = listOf("SYMPTOMS"), edit = listOf("SYMPTOMS"))

  @Test
  fun caregiverAllowedToEditAndDelete_changesAnySymptom_signedWithTheirUid(): Unit = runBlocking {
    letCarolEditSymptoms()
    val alices = alicesSymptom()
    val carols = journal(aliceUid).document().also { allowed(it.set(carolsSymptom())) }

    allowed(alices.update(carolsEdit()))
    allowed(alices.delete())
    // Who added it never changes, even for the caregiver who did
    allowed(carols.update(carolsEdit()))
    denied(carols.update(carolsEdit("addedByName" to "Bob")))
    allowed(carols.delete())
  }

  @Test
  fun caregiverWithoutTheSwitch_orWithoutAdding_orSharing_cantEditOrDelete(): Unit = runBlocking {
    val alices = alicesSymptom()
    val cases =
        listOf(
            // The switch is off
            Triple(listOf("SYMPTOMS"), listOf("SYMPTOMS"), emptyList()),
            // Allowed in another section only
            Triple(
                listOf("SYMPTOMS", "PLANNING"),
                listOf("SYMPTOMS", "PLANNING"),
                listOf("PLANNING"),
            ),
            // The switch needs adding, and adding needs sharing
            Triple(listOf("SYMPTOMS"), emptyList(), listOf("SYMPTOMS")),
            Triple(emptyList(), listOf("SYMPTOMS"), listOf("SYMPTOMS")),
        )
    for ((see, add, edit) in cases) {
      shareWithCarol(see, add, edit)
      denied(alices.update(carolsEdit()))
      denied(alices.delete())
    }
  }

  @Test
  fun caregiver_cantEditInSomeoneElsesName_orBreakTheValidation(): Unit = runBlocking {
    letCarolEditSymptoms()
    val alices = alicesSymptom()
    val invalid =
        listOf(
            carolsEdit("editedBy" to aliceUid),
            carolsEdit("editedBy" to null),
            carolsEdit("editedByName" to null),
            carolsEdit("editedByName" to "  "),
            carolsEdit("editedByName" to "a".repeat(51)),
            carolsEdit("addedBy" to carolUid, "addedByName" to "Carol"),
            carolsEdit("types" to emptyList<String>()),
            carolsEdit("createdAt" to Timestamp.now()),
        )
    for (fields in invalid) denied(alices.update(fields))
  }

  @Test
  fun removedCaregiver_orStranger_cantEditOrDelete(): Unit = runBlocking {
    letCarolEditSymptoms()
    val alices = alicesSymptom()
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(alices.update(carolsEdit()))
    denied(alices.delete())

    EmulatorTestData.signIn("symptom-dave")
    denied(alices.update(mapOf("intensity" to 2, "editedBy" to daveUid, "editedByName" to "Dave")))
    denied(alices.delete())
  }

  @Test
  fun newSymptom_cantSayItWasEdited(): Unit = runBlocking {
    letCarolEditSymptoms()
    denied(
        journal(aliceUid)
            .document()
            .set(carolsSymptom("editedBy" to carolUid, "editedByName" to "Carol"))
    )
    EmulatorTestData.signIn("symptom-alice")
    denied(journal(aliceUid).document().set(symptom("editedByName" to "Alice")))
  }

  @Test
  fun patientEdit_removesTheCaregiversName_andCantSignAsEditor(): Unit = runBlocking {
    letCarolEditSymptoms()
    val alices = alicesSymptom()
    allowed(alices.update(carolsEdit()))
    EmulatorTestData.signIn("symptom-alice")

    denied(alices.update(mapOf("intensity" to 3)))
    denied(alices.update(mapOf("editedBy" to aliceUid, "editedByName" to "Alice")))
    allowed(
        alices.update(
            mapOf(
                "intensity" to 3,
                "editedBy" to FieldValue.delete(),
                "editedByName" to FieldValue.delete(),
            )
        )
    )
  }
}
