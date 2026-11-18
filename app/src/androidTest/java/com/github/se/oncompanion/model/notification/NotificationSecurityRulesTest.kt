// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests firestore.rules for /users/{uid}/notifications/{id} (US-50) through the client SDK: the
 * owner reads theirs and marks them seen, nothing else, and nobody deletes one (Firestore's TTL
 * does). Who may create one is in [CaregiverNotificationSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class NotificationSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String

  private val notificationId = "PRESCRIPTION_ADDED_presc-1"

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    bobUid = EmulatorTestData.createUser("bob")
    aliceUid = EmulatorTestData.createUser("alice") // alice stays signed in
    // The server's notification for alice: Bob added prescription presc-1
    val now = Instant.now()
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/notifications",
        notificationId,
        """{
          "kind": {"stringValue": "PRESCRIPTION_ADDED"},
          "itemId": {"stringValue": "presc-1"},
          "actorUid": {"stringValue": "$bobUid"},
          "actorFirstName": {"stringValue": "Bob"},
          "createdAt": {"timestampValue": "$now"},
          "expiresAt": {"timestampValue": "${now.plus(Duration.ofDays(7))}"},
          "seen": {"booleanValue": false}
        }""",
    )
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  private fun notifications(uid: String): CollectionReference =
      db.collection("users").document(uid).collection("notifications")

  /** A notification as the server writes it, for create attempts. */
  private fun validNotification(): Map<String, Any?> =
      mapOf(
          "kind" to "PRESCRIPTION_ADDED",
          "itemId" to "presc-2",
          "actorUid" to bobUid,
          "actorFirstName" to "Bob",
          "createdAt" to FieldValue.serverTimestamp(),
          "expiresAt" to Timestamp.now(),
          "seen" to false,
      )

  @Test
  fun ownerReadsTheirNotifications(): Unit = runBlocking {
    assertTrue(
        allowed(notifications(aliceUid).document(notificationId).get(Source.SERVER)).exists()
    )
    assertEquals(1, allowed(notifications(aliceUid).get(Source.SERVER)).size())
  }

  @Test
  fun nobodyElseReadsThem(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(notifications(aliceUid).document(notificationId).get(Source.SERVER))
    denied(notifications(aliceUid).get(Source.SERVER))
    EmulatorTestData.signOut()
    denied(notifications(aliceUid).get(Source.SERVER))
  }

  @Test
  fun ownerMarksOneSeen_andMarkingItAgainIsAllowed(): Unit = runBlocking {
    val doc = notifications(aliceUid).document(notificationId)
    allowed(doc.update("seen", true))
    allowed(doc.update("seen", true))
    assertEquals(true, allowed(doc.get(Source.SERVER)).getBoolean("seen"))
  }

  @Test
  fun ownerChangesNothingButSeen_andNeverBackToUnseen(): Unit = runBlocking {
    val doc = notifications(aliceUid).document(notificationId)
    val changes =
        listOf(
            mapOf("seen" to false),
            mapOf("kind" to "PRESCRIPTION_CONFIRMED"),
            mapOf("itemId" to "presc-9"),
            mapOf("actorUid" to aliceUid),
            mapOf("actorFirstName" to "Mallory"),
            mapOf("createdAt" to Timestamp.now()),
            mapOf("expiresAt" to Timestamp(Instant.now().plus(Duration.ofDays(365)))),
            mapOf("seen" to true, "medication" to "Ondansetron 8 mg"),
        )
    for (change in changes) denied(doc.update(change))
  }

  @Test
  fun bobCannotMarkAlicesNotificationSeen(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(notifications(aliceUid).document(notificationId).update("seen", true))
  }

  @Test
  fun thePatientForHerself_andSomeoneOutsideTheCircle_createNone(): Unit = runBlocking {
    denied(notifications(aliceUid).document("PRESCRIPTION_ADDED_presc-2").set(validNotification()))
    EmulatorTestData.signIn("bob")
    denied(notifications(aliceUid).document("PRESCRIPTION_ADDED_presc-2").set(validNotification()))
  }

  @Test
  fun theAppDeletesNoNotification(): Unit = runBlocking {
    denied(notifications(aliceUid).document(notificationId).delete())
    EmulatorTestData.signIn("bob")
    denied(notifications(aliceUid).document(notificationId).delete())
  }
}
