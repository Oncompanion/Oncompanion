// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that firestore.rules let a caregiver confirm the patient's registration for an event only
 * when the patient shares Events and lets them confirm (US-42): signed with their own uid, for an
 * upcoming event, never over an existing confirmation, and never removed by them. alice is the
 * patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverEventRegistrationSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String
  private lateinit var daveUid: String

  // Emulator data is shared between runs: each test uses its own events
  private val runId = UUID.randomUUID().toString()
  private val upcomingId = "caregiver-upcoming-$runId"
  private val startedId = "caregiver-started-$runId"
  private val missingId = "caregiver-missing-$runId"

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    // Events are added from the Firebase console, never by the app: written bypassing the rules
    addEvent(upcomingId, Instant.now().plus(Duration.ofDays(1)))
    addEvent(startedId, Instant.now().minus(Duration.ofHours(1)))
    daveUid = EmulatorTestData.createUser("registration-dave")
    aliceUid = EmulatorTestData.createUser("registration-alice")
    carolUid = EmulatorTestData.createUser("registration-carol") // carol stays signed in
    shareWithCarol(see = listOf("EVENTS"), add = listOf("EVENTS"))
  }

  @After
  fun tearDown(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument("events/$upcomingId")
    EmulatorTestData.deleteRawDocument("events/$startedId")
    EmulatorTestData.signOut()
  }

  private suspend fun addEvent(id: String, startAt: Instant) {
    EmulatorTestData.createRawDocument(
        "events",
        id,
        """{"title": {"stringValue": "Gentle yoga"}, "startAt": {"timestampValue": "$startAt"}}""",
    )
  }

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

  private fun registrations(uid: String = aliceUid): CollectionReference =
      db.collection("users")
          .document(uid)
          .collection(EventRegistrationRepositoryFirestore.COLLECTION)

  /** A confirmation, with [extra] fields (null removes one). */
  private fun confirmation(vararg extra: Pair<String, Any?>): Map<String, Any?> =
      (mapOf<String, Any?>("confirmedAt" to FieldValue.serverTimestamp()) + extra).filterValues {
        it != null
      }

  /** A confirmation signed by the signed-in caregiver, with [extra] fields. */
  private fun carolsConfirmation(vararg extra: Pair<String, Any?>) =
      confirmation("addedBy" to carolUid, "addedByName" to "Carol", *extra)

  @Test
  fun caregiverAllowedToConfirm_confirmsAnUpcomingEvent_signedWithTheirUid(): Unit = runBlocking {
    allowed(registrations().document(upcomingId).set(carolsConfirmation()))

    val saved = allowed(registrations().document(upcomingId).get(Source.SERVER))
    assertEquals(carolUid, saved.getString("addedBy"))
    assertEquals("Carol", saved.getString("addedByName"))
  }

  @Test
  fun caregiversConfirmation_throughTheRepository_readsBackSigned(): Unit = runBlocking {
    val repository = EventRegistrationRepositoryFirestore(db)

    repository.confirm(aliceUid, upcomingId, EventRegistration(carolUid, "Carol"))
    // Accepted by the server, not only saved on the device
    withTimeout(10_000) { db.waitForPendingWrites().await() }

    assertEquals(
        EventRegistration(carolUid, "Carol"),
        withTimeout(10_000) { repository.observeRegistration(aliceUid, upcomingId).first() },
    )
    assertEquals(
        carolUid,
        allowed(registrations().document(upcomingId).get(Source.SERVER)).getString("addedBy"),
    )
  }

  @Test
  fun caregiverWithoutCanAdd_orNotSeeingEvents_cantConfirm(): Unit = runBlocking {
    val cases =
        listOf(
            listOf("EVENTS") to emptyList(),
            // Adding to another section isn't confirming events
            listOf("EVENTS", "SYMPTOMS") to listOf("SYMPTOMS"),
            // canAdd alone isn't enough: the member must also see Events
            listOf("PLANNING") to listOf("EVENTS"),
        )
    for ((see, add) in cases) {
      shareWithCarol(see, add)
      denied(registrations().document(upcomingId).set(carolsConfirmation()))
    }
  }

  @Test
  fun caregiver_cantConfirmAStartedOrMissingEvent(): Unit = runBlocking {
    for (eventId in listOf(startedId, missingId)) {
      denied(registrations().document(eventId).set(carolsConfirmation()))
    }
  }

  @Test
  fun caregiver_cantSignInSomeoneElsesName_orLeaveTheNameOut(): Unit = runBlocking {
    val forged =
        listOf(
            carolsConfirmation("addedBy" to aliceUid),
            carolsConfirmation("addedBy" to null),
            carolsConfirmation("addedByName" to null),
            carolsConfirmation("addedByName" to "  "),
            carolsConfirmation("addedByName" to "a".repeat(51)),
            carolsConfirmation("eventTitle" to "Gentle yoga"),
        )
    for (fields in forged) denied(registrations().document(upcomingId).set(fields))
  }

  @Test
  fun caregiver_cantReplaceAConfirmation_norRemoveOne_evenTheirOwn(): Unit = runBlocking {
    // The patient's own
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/${EventRegistrationRepositoryFirestore.COLLECTION}",
        upcomingId,
        """{"confirmedAt": {"timestampValue": "${Instant.now()}"}}""",
    )
    denied(registrations().document(upcomingId).set(carolsConfirmation()))
    denied(registrations().document(upcomingId).delete())

    // Carol's own
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${EventRegistrationRepositoryFirestore.COLLECTION}/$upcomingId"
    )
    allowed(registrations().document(upcomingId).set(carolsConfirmation()))
    denied(registrations().document(upcomingId).set(carolsConfirmation()))
    denied(registrations().document(upcomingId).delete())
  }

  @Test
  fun removedCaregiver_orStranger_cantConfirm(): Unit = runBlocking {
    EmulatorTestData.deleteRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}/$carolUid"
    )
    denied(registrations().document(upcomingId).set(carolsConfirmation()))

    EmulatorTestData.signIn("registration-dave")
    denied(
        registrations()
            .document(upcomingId)
            .set(confirmation("addedBy" to daveUid, "addedByName" to "Dave"))
    )
  }

  @Test
  fun patient_keepsWhoConfirmed_removesIt_andConfirmsAgainUnsigned(): Unit = runBlocking {
    allowed(registrations().document(upcomingId).set(carolsConfirmation()))

    EmulatorTestData.signIn("registration-alice")
    // Alice can't sign her own, nor take carol's name off hers by confirming over it
    denied(registrations().document(upcomingId).set(confirmation()))
    denied(
        registrations()
            .document(upcomingId)
            .set(confirmation("addedBy" to aliceUid, "addedByName" to "Alice"))
    )

    allowed(registrations().document(upcomingId).delete())
    allowed(registrations().document(upcomingId).set(confirmation()))
    val saved = allowed(registrations().document(upcomingId).get(Source.SERVER))
    assertNull(saved.getString("addedBy"))
  }
}
