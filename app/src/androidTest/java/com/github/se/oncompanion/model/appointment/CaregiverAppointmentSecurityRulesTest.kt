// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.appointment

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that firestore.rules let a caregiver add an appointment to a patient's planning only when
 * the patient shares Planning and lets them add (US-41), signed with their own uid, and edit or
 * delete one only when the patient also lets them edit and delete there (US-46), signing their
 * edit. alice is the patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverAppointmentSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String
  private lateinit var daveUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    daveUid = EmulatorTestData.createUser("appointment-dave")
    aliceUid = EmulatorTestData.createUser("appointment-alice")
    carolUid = EmulatorTestData.createUser("appointment-carol") // carol stays signed in
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PLANNING"))
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

  private fun planning(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(AppointmentRepositoryFirestore.COLLECTION)

  /** A valid new appointment, with [extra] fields (null removes one). */
  private fun appointment(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      (mapOf(
              "title" to "Oncology consultation",
              "scheduledAt" to Timestamp(Instant.parse("2026-12-01T09:00:00Z")),
              "type" to "CONSULTATION",
              "createdAt" to FieldValue.serverTimestamp(),
          ) + extra)
          .filterValues { it != null }

  /** An appointment signed by the signed-in caregiver, with [extra] fields. */
  private fun carolsAppointment(vararg extra: Pair<String, Any?>) =
      appointment("addedBy" to carolUid, "addedByName" to "Carol", *extra)

  @Test
  fun caregiverAllowedToAdd_addsAnAppointmentSignedWithTheirUid(): Unit = runBlocking {
    allowed(planning(aliceUid).document().set(carolsAppointment()))
  }

  @Test
  fun caregiverWithoutCanAdd_orNotSeeingPlanning_cantAdd(): Unit = runBlocking {
    shareWithCarol(see = listOf("PLANNING"), add = emptyList())
    denied(planning(aliceUid).document().set(carolsAppointment()))

    // Adding to another section isn't adding to Planning
    shareWithCarol(see = listOf("PLANNING", "SYMPTOMS"), add = listOf("SYMPTOMS"))
    denied(planning(aliceUid).document().set(carolsAppointment()))

    // canAdd alone isn't enough: the member must also see the section
    shareWithCarol(see = listOf("SYMPTOMS"), add = listOf("PLANNING"))
    denied(planning(aliceUid).document().set(carolsAppointment()))
  }

  @Test
  fun caregiver_cantSignInSomeoneElsesName_orLeaveTheNameOut(): Unit = runBlocking {
    val forged =
        listOf(
            carolsAppointment("addedBy" to aliceUid),
            carolsAppointment("addedBy" to null),
            carolsAppointment("addedByName" to null),
            carolsAppointment("addedByName" to "  "),
            carolsAppointment("addedByName" to "a".repeat(51)),
        )
    for (fields in forged) denied(planning(aliceUid).document().set(fields))
  }

  @Test
  fun caregiver_followsTheAppointmentValidation(): Unit = runBlocking {
    val invalid =
        listOf(
            carolsAppointment("title" to "  "),
            carolsAppointment("type" to "SURGERY"),
            carolsAppointment("notes" to "a".repeat(1001)),
            carolsAppointment("createdAt" to Timestamp.now()),
        )
    for (fields in invalid) denied(planning(aliceUid).document().set(fields))
  }

  @Test
  fun removedCaregiver_orStranger_cantAdd(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(planning(aliceUid).document().set(carolsAppointment()))

    EmulatorTestData.signIn("appointment-dave")
    denied(
        planning(aliceUid)
            .document()
            .set(appointment("addedBy" to daveUid, "addedByName" to "Dave"))
    )
  }

  @Test
  fun caregiver_cantEditOrDelete_evenTheirOwnAppointment(): Unit = runBlocking {
    val added = planning(aliceUid).document()
    allowed(added.set(carolsAppointment()))

    denied(added.update("notes", "Changed"))
    denied(added.delete())
  }

  @Test
  fun patient_cantSignTheirOwnAppointment_butKeepsAddingAsBefore(): Unit = runBlocking {
    EmulatorTestData.signIn("appointment-alice")

    allowed(planning(aliceUid).document().set(appointment()))
    denied(
        planning(aliceUid)
            .document()
            .set(appointment("addedBy" to aliceUid, "addedByName" to "Alice"))
    )
  }

  @Test
  fun patient_correctsACaregiversAppointment_withoutChangingWhoAddedIt(): Unit = runBlocking {
    val added = planning(aliceUid).document()
    allowed(added.set(carolsAppointment()))
    EmulatorTestData.signIn("appointment-alice")

    allowed(added.update("location", "HUG, building B"))
    denied(added.update("addedByName", "Bob"))
    denied(added.update("addedBy", FieldValue.delete()))
    allowed(added.delete())
  }

  /** An appointment alice added herself, before carol signs in again. */
  private suspend fun alicesAppointment() =
      planning(aliceUid).document().also {
        EmulatorTestData.signIn("appointment-alice")
        allowed(it.set(appointment()))
        EmulatorTestData.signIn("appointment-carol")
      }

  /**
   * Carol's edit of an appointment, signed with her uid, with [extra] fields (null removes one).
   */
  private fun carolsEdit(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      mapOf("notes" to "Bring the blood test", "editedBy" to carolUid, "editedByName" to "Carol") +
          extra.map { (key, value) -> key to (value ?: FieldValue.delete()) }

  @Test
  fun caregiverAllowedToEditAndDelete_changesAnyAppointment_signedWithTheirUid(): Unit =
      runBlocking {
        shareWithCarol(
            see = listOf("PLANNING"),
            add = listOf("PLANNING"),
            edit = listOf("PLANNING"),
        )
        val alices = alicesAppointment()
        val carols = planning(aliceUid).document().also { allowed(it.set(carolsAppointment())) }

        allowed(alices.update(carolsEdit()))
        allowed(alices.delete())
        // Who added it never changes, even for the caregiver who did
        allowed(carols.update(carolsEdit()))
        denied(carols.update(carolsEdit("addedByName" to "Bob")))
        allowed(carols.delete())
      }

  @Test
  fun caregiverWithoutTheSwitch_orWithoutAdding_orSharing_cantEditOrDelete(): Unit = runBlocking {
    val alices = alicesAppointment()
    val cases =
        listOf(
            // The switch is off
            Triple(listOf("PLANNING"), listOf("PLANNING"), emptyList()),
            // Allowed in another section only
            Triple(
                listOf("PLANNING", "SYMPTOMS"),
                listOf("PLANNING", "SYMPTOMS"),
                listOf("SYMPTOMS"),
            ),
            // The switch needs adding, and adding needs sharing
            Triple(listOf("PLANNING"), emptyList(), listOf("PLANNING")),
            Triple(emptyList(), listOf("PLANNING"), listOf("PLANNING")),
        )
    for ((see, add, edit) in cases) {
      shareWithCarol(see, add, edit)
      denied(alices.update(carolsEdit()))
      denied(alices.delete())
    }
  }

  @Test
  fun caregiver_cantEditInSomeoneElsesName_orBreakTheValidation(): Unit = runBlocking {
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PLANNING"), edit = listOf("PLANNING"))
    val alices = alicesAppointment()
    val invalid =
        listOf(
            carolsEdit("editedBy" to aliceUid),
            carolsEdit("editedBy" to null),
            carolsEdit("editedByName" to null),
            carolsEdit("editedByName" to "  "),
            carolsEdit("editedByName" to "a".repeat(51)),
            carolsEdit("addedBy" to carolUid, "addedByName" to "Carol"),
            carolsEdit("title" to "  "),
            carolsEdit("createdAt" to Timestamp.now()),
        )
    for (fields in invalid) denied(alices.update(fields))
  }

  @Test
  fun removedCaregiver_orStranger_cantEditOrDelete(): Unit = runBlocking {
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PLANNING"), edit = listOf("PLANNING"))
    val alices = alicesAppointment()
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(alices.update(carolsEdit()))
    denied(alices.delete())

    EmulatorTestData.signIn("appointment-dave")
    denied(
        alices.update(mapOf("notes" to "Changed", "editedBy" to daveUid, "editedByName" to "Dave"))
    )
    denied(alices.delete())
  }

  @Test
  fun newAppointment_cantSayItWasEdited(): Unit = runBlocking {
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PLANNING"), edit = listOf("PLANNING"))
    denied(
        planning(aliceUid)
            .document()
            .set(carolsAppointment("editedBy" to carolUid, "editedByName" to "Carol"))
    )
    EmulatorTestData.signIn("appointment-alice")
    denied(planning(aliceUid).document().set(appointment("editedByName" to "Alice")))
  }

  @Test
  fun patientEdit_removesTheCaregiversName_orKeepsIt_butCantChangeIt(): Unit = runBlocking {
    shareWithCarol(see = listOf("PLANNING"), add = listOf("PLANNING"), edit = listOf("PLANNING"))
    val alices = alicesAppointment()
    allowed(alices.update(carolsEdit()))
    EmulatorTestData.signIn("appointment-alice")

    denied(alices.update(mapOf("editedBy" to aliceUid, "editedByName" to "Alice")))
    denied(alices.update(mapOf("editedByName" to "Bob")))
    denied(alices.update(mapOf("editedByName" to FieldValue.delete())))
    // An older version of the app leaves the editor as it was
    allowed(alices.update(mapOf("notes" to "Mine")))
    allowed(
        alices.update(
            mapOf(
                "notes" to "Mine",
                "editedBy" to FieldValue.delete(),
                "editedByName" to FieldValue.delete(),
            )
        )
    )
  }
}
