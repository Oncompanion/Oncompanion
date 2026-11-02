// Co-authored-by: Claude Code <noreply@anthropic.com>
package com.github.se.oncompanion.model.event

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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests firestore.rules for /users/{uid}/eventRegistrations/{eventId} through the client SDK. */
@RunWith(AndroidJUnit4::class)
class EventRegistrationSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var ownerUid: String
  private lateinit var otherUid: String

  // Emulator data is shared between runs: each test uses its own events
  private val runId = UUID.randomUUID().toString()
  private val upcomingId = "upcoming-$runId"
  private val startedId = "started-$runId"
  private val missingId = "missing-$runId"

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    // Events are added from the Firebase console, never by the app: written bypassing the rules
    addEvent(upcomingId, Instant.now().plus(Duration.ofDays(1)))
    addEvent(startedId, Instant.now().minus(Duration.ofHours(1)))
    otherUid = EmulatorTestData.createUser("registration-other")
    ownerUid = EmulatorTestData.createUser("registration-owner") // stays signed in
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
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

  /** Writes a confirmation for [eventId] as [uid], bypassing the rules, e.g. one made earlier. */
  private suspend fun addRawRegistration(uid: String, eventId: String) {
    EmulatorTestData.createRawDocument(
        "users/$uid/eventRegistrations",
        eventId,
        """{"confirmedAt": {"timestampValue": "${Instant.now()}"}}""",
    )
  }

  private fun registrations(uid: String = ownerUid): CollectionReference =
      db.collection("users").document(uid).collection("eventRegistrations")

  private fun confirmation(): Map<String, Any> =
      mapOf("confirmedAt" to FieldValue.serverTimestamp())

  // ---- owner ----

  @Test
  fun ownerCanConfirmAnUpcomingEvent_andReadIt(): Unit = runBlocking {
    allowed(registrations().document(upcomingId).set(confirmation()))

    assertTrue(allowed(registrations().document(upcomingId).get(Source.SERVER)).exists())
    assertEquals(
        listOf(upcomingId),
        allowed(registrations().get(Source.SERVER)).documents.map { it.id },
    )
  }

  @Test
  fun ownerCanConfirmTwice_withoutADuplicate(): Unit = runBlocking {
    allowed(registrations().document(upcomingId).set(confirmation()))
    allowed(registrations().document(upcomingId).set(confirmation()))

    assertEquals(1, allowed(registrations().get(Source.SERVER)).size())
  }

  @Test
  fun ownerCanRemove_evenAfterTheEventStarted(): Unit = runBlocking {
    allowed(registrations().document(upcomingId).set(confirmation()))
    addRawRegistration(ownerUid, startedId)

    allowed(registrations().document(upcomingId).delete())
    allowed(registrations().document(startedId).delete())

    assertFalse(allowed(registrations().document(upcomingId).get(Source.SERVER)).exists())
    assertFalse(allowed(registrations().document(startedId).get(Source.SERVER)).exists())
  }

  // ---- which events can be confirmed ----

  @Test
  fun startedEvent_cannotBeConfirmed(): Unit = runBlocking {
    denied(registrations().document(startedId).set(confirmation()))
  }

  @Test
  fun missingEvent_cannotBeConfirmed(): Unit = runBlocking {
    denied(registrations().document(missingId).set(confirmation()))
  }

  // ---- document shape ----

  @Test
  fun extraFields_areDenied(): Unit = runBlocking {
    denied(
        registrations().document(upcomingId).set(confirmation() + ("eventTitle" to "Gentle yoga"))
    )
  }

  @Test
  fun missingConfirmedAt_isDenied(): Unit = runBlocking {
    denied(registrations().document(upcomingId).set(emptyMap<String, Any>()))
  }

  @Test
  fun confirmedAtOtherThanTheServerTime_isDenied(): Unit = runBlocking {
    val yesterday = Timestamp(Instant.now().minus(Duration.ofDays(1)))
    denied(registrations().document(upcomingId).set(mapOf("confirmedAt" to yesterday)))
  }

  // ---- other users ----

  @Test
  fun otherUser_cannotReadConfirmOrRemove(): Unit = runBlocking {
    addRawRegistration(ownerUid, upcomingId)
    EmulatorTestData.signIn("registration-other")

    denied(registrations(ownerUid).document(upcomingId).get(Source.SERVER))
    denied(registrations(ownerUid).get(Source.SERVER))
    denied(registrations(ownerUid).document(upcomingId).set(confirmation()))
    denied(registrations(ownerUid).document(upcomingId).delete())
  }

  @Test
  fun signedOut_cannotReadOrWrite(): Unit = runBlocking {
    addRawRegistration(ownerUid, upcomingId)
    EmulatorTestData.signOutAndWaitForFirestore(ownerUid)

    denied(registrations(ownerUid).document(upcomingId).get(Source.SERVER))
    denied(registrations(ownerUid).document(upcomingId).set(confirmation()))
    denied(registrations(ownerUid).document(upcomingId).delete())
  }
}
