// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that firestore.rules let a caregiver notify the patient of a prescription they added for
 * them (US-50), and nothing else: about their own prescription still waiting for the patient,
 * signed with their own uid, once. alice is the patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverNotificationSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String
  private lateinit var daveUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    daveUid = EmulatorTestData.createUser("notif-dave")
    aliceUid = EmulatorTestData.createUser("notif-alice")
    carolUid = EmulatorTestData.createUser("notif-carol") // carol stays signed in
    letCarolAddPrescriptions(add = listOf("PRESCRIPTIONS"))
    // Carol's prescription for alice, waiting for her review
    allowed(prescription("presc-1").set(prescriptionFields(addedBy = carolUid)))
  }

  @After fun tearDown(): Unit = runBlocking { EmulatorTestData.signOut() }

  /** Alice lets carol see her prescriptions and add to [add] (bypassing the rules). */
  private suspend fun letCarolAddPrescriptions(add: List<String>) {
    val canAdd = add.joinToString(", ", "[", "]") { """{"stringValue": "$it"}""" }
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": [{"stringValue": "PRESCRIPTIONS"}]}},
            "canAdd": {"arrayValue": {"values": $canAdd}},
            "inviteCode": {"stringValue": "ABCDEFGHJK"}}""",
    )
  }

  private fun prescription(id: String): DocumentReference =
      db.collection("users")
          .document(aliceUid)
          .collection(MedicationRepositoryFirestore.PRESCRIPTIONS)
          .document(id)

  private fun prescriptionFields(addedBy: String) =
      mapOf(
          "prescribedOn" to Timestamp.now(),
          "createdAt" to FieldValue.serverTimestamp(),
          "medications" to listOf(mapOf("id" to "m1", "name" to "Ondansetron 8 mg")),
          "addedBy" to addedBy,
          "addedByName" to "Carol",
          "confirmation" to "PENDING",
      )

  private fun notification(id: String = "PRESCRIPTION_ADDED_presc-1"): DocumentReference =
      db.collection("users").document(aliceUid).collection("notifications").document(id)

  /** The notification carol's phone writes about presc-1, with [changes] (null removes a field). */
  private fun carolsNotification(vararg changes: Pair<String, Any?>): Map<String, Any?> =
      (mapOf(
              "kind" to "PRESCRIPTION_ADDED",
              "itemId" to "presc-1",
              "actorUid" to carolUid,
              "actorFirstName" to "Carol",
              "createdAt" to FieldValue.serverTimestamp(),
              "expiresAt" to inDays(7),
              "seen" to false,
          ) + changes)
          .filterValues { it != null }

  private fun inDays(days: Long) = Timestamp(Instant.now().plus(Duration.ofDays(days)))

  @Test
  fun caregiverNotifiesThePatient_ofTheirPrescriptionWaitingForReview(): Unit = runBlocking {
    allowed(notification().set(carolsNotification()))
  }

  @Test
  fun anExpiryEarlierThanSevenDays_isAccepted_asAfterSyncingLate(): Unit = runBlocking {
    // Saved offline on Monday, synced on Thursday: the phone counted its 7 days from Monday
    allowed(notification().set(carolsNotification("expiresAt" to inDays(4))))
  }

  @Test
  fun eachInvalidField_isRefused(): Unit = runBlocking {
    val invalid =
        listOf(
            "kind" to "PRESCRIPTION_CONFIRMED",
            "kind" to null,
            "itemId" to "presc-2",
            "itemId" to null,
            "actorUid" to aliceUid,
            "actorUid" to daveUid,
            "actorUid" to null,
            "actorFirstName" to "  ",
            "actorFirstName" to "a".repeat(51),
            "actorFirstName" to null,
            "createdAt" to Timestamp.now(),
            "createdAt" to null,
            "expiresAt" to inDays(10),
            "expiresAt" to "next week",
            "expiresAt" to null,
            "seen" to true,
            "seen" to null,
            "medication" to "Ondansetron 8 mg",
        )
    for (change in invalid) denied(notification().set(carolsNotification(change)))
  }

  @Test
  fun theIdMustBeTheKindAndTheItem(): Unit = runBlocking {
    denied(notification("presc-1").set(carolsNotification()))
    denied(notification("PRESCRIPTION_ADDED_presc-1-again").set(carolsNotification()))
  }

  @Test
  fun onlyAboutHerOwnPrescription_stillWaitingForThePatient(): Unit = runBlocking {
    // No such prescription
    denied(
        notification("PRESCRIPTION_ADDED_presc-9").set(carolsNotification("itemId" to "presc-9"))
    )
    // Another member's prescription (written bypassing the rules)
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/${MedicationRepositoryFirestore.PRESCRIPTIONS}",
        "presc-dave",
        rawPrescription(addedBy = daveUid, confirmation = "PENDING"),
    )
    denied(
        notification("PRESCRIPTION_ADDED_presc-dave")
            .set(carolsNotification("itemId" to "presc-dave"))
    )
    // Her own, already confirmed by alice
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/${MedicationRepositoryFirestore.PRESCRIPTIONS}",
        "presc-confirmed",
        rawPrescription(addedBy = carolUid, confirmation = "CONFIRMED"),
    )
    denied(
        notification("PRESCRIPTION_ADDED_presc-confirmed")
            .set(carolsNotification("itemId" to "presc-confirmed"))
    )
  }

  @Test
  fun caregiverNoLongerAllowedToAdd_strangerAndPatient_cantNotify(): Unit = runBlocking {
    letCarolAddPrescriptions(add = emptyList())
    denied(notification().set(carolsNotification()))

    EmulatorTestData.signIn("notif-dave")
    denied(notification().set(carolsNotification("actorUid" to daveUid)))

    EmulatorTestData.signIn("notif-alice")
    denied(notification().set(carolsNotification("actorUid" to aliceUid)))
  }

  @Test
  fun aNotificationIsSentOnce_andTheCaregiverCantChangeIt(): Unit = runBlocking {
    allowed(notification().set(carolsNotification()))
    // Sending it again would replace it: only the patient may change a notification
    denied(notification().set(carolsNotification()))
    denied(notification().update("seen", true))
    denied(notification().delete())
  }

  private fun rawPrescription(addedBy: String, confirmation: String) =
      """{"prescribedOn": {"timestampValue": "${Instant.now()}"},
          "createdAt": {"timestampValue": "${Instant.now()}"},
          "medications": {"arrayValue": {"values": [{"mapValue": {"fields": {
              "id": {"stringValue": "m1"}, "name": {"stringValue": "Ondansetron 8 mg"}}}}]}},
          "addedBy": {"stringValue": "$addedBy"},
          "addedByName": {"stringValue": "Someone"},
          "confirmation": {"stringValue": "$confirmation"}}"""
}
