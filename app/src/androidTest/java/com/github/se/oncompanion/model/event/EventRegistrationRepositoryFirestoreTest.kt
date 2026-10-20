// Co-authored-by: Claude Code <noreply@anthropic.com>
package com.github.se.oncompanion.model.event

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore.Companion.FIELD_CONFIRMED_AT
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore.Companion.USERS_COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [EventRegistrationRepositoryFirestore] against the Firestore emulator. Who can read and
 * write confirmations is tested in [EventRegistrationSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class EventRegistrationRepositoryFirestoreTest {

  private val db
    get() = FirebaseEmulator.firestore

  private val repository = EventRegistrationRepositoryFirestore(db)
  private lateinit var uid: String

  // Emulator data is shared between runs: each test uses its own events
  private val runId = UUID.randomUUID().toString()
  private val upcomingId = "upcoming-$runId"
  private val otherUpcomingId = "other-upcoming-$runId"
  private val startedId = "started-$runId"

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    addEvent(upcomingId, Instant.now().plus(Duration.ofDays(1)))
    addEvent(otherUpcomingId, Instant.now().plus(Duration.ofDays(2)))
    addEvent(startedId, Instant.now().minus(Duration.ofHours(1)))
    uid = EmulatorTestData.createUser("registration-repo") // stays signed in
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    listOf(upcomingId, otherUpcomingId, startedId).forEach {
      EmulatorTestData.deleteRawDocument("events/$it")
    }
    EmulatorTestData.signOut()
  }

  /** Writes an event bypassing the rules, like an event added from the Firebase console. */
  private suspend fun addEvent(id: String, startAt: Instant) {
    EmulatorTestData.createRawDocument(
        "events",
        id,
        """{"title": {"stringValue": "Gentle yoga"}, "startAt": {"timestampValue": "$startAt"}}""",
    )
  }

  private fun registrations() = db.collection(USERS_COLLECTION).document(uid).collection(COLLECTION)

  /** Waits until [eventId]'s confirmation is observed as [expected]. */
  private suspend fun awaitRegistered(
      eventId: String,
      expected: Boolean,
      repo: EventRegistrationRepository = repository,
  ) {
    withTimeout(10_000) { repo.observeIsRegistered(uid, eventId).first { it == expected } }
  }

  /** Waits until the server has acknowledged (or rejected) every write made on this phone. */
  private suspend fun awaitSynced() {
    withTimeout(10_000) { db.waitForPendingWrites().await() }
  }

  @Test
  fun constantsMatchTheFirestoreLayout() {
    assertEquals("users", USERS_COLLECTION)
    assertEquals("eventRegistrations", COLLECTION)
    assertEquals("confirmedAt", FIELD_CONFIRMED_AT)
  }

  @Test
  fun nothingIsConfirmed_atFirst(): Unit = runBlocking {
    assertFalse(withTimeout(10_000) { repository.observeIsRegistered(uid, upcomingId).first() })
  }

  @Test
  fun confirm_isObserved_andSavedOnTheServer_withItsTime(): Unit = runBlocking {
    repository.confirm(uid, upcomingId)

    awaitRegistered(upcomingId, true)
    awaitSynced()
    val saved = registrations().document(upcomingId).get(Source.SERVER).await()
    assertEquals(setOf(FIELD_CONFIRMED_AT), saved.data?.keys)
    assertNotNull(saved.getTimestamp(FIELD_CONFIRMED_AT))
  }

  @Test
  fun confirmingTwice_keepsOneConfirmation(): Unit = runBlocking {
    repository.confirm(uid, upcomingId)
    repository.confirm(uid, upcomingId)
    awaitSynced()

    val saved = registrations().get(Source.SERVER).await()
    assertEquals(listOf(upcomingId), saved.documents.map { it.id })
  }

  @Test
  fun remove_isObserved_andLeavesOtherConfirmations(): Unit = runBlocking {
    repository.confirm(uid, upcomingId)
    repository.confirm(uid, otherUpcomingId)
    awaitSynced()

    repository.remove(uid, upcomingId)

    awaitRegistered(upcomingId, false)
    awaitSynced()
    val saved = registrations().get(Source.SERVER).await()
    assertEquals(listOf(otherUpcomingId), saved.documents.map { it.id })
  }

  @Test
  fun startedEvent_isRolledBack_whenTheServerRejectsIt(): Unit = runBlocking {
    // Offline, so the confirmation is observed on the phone before the server can reject it
    db.disableNetwork().await()
    repository.confirm(uid, startedId)
    awaitRegistered(startedId, true)

    db.enableNetwork().await()
    awaitRegistered(startedId, false)
  }

  @Test
  fun offline_confirmAndRemoveWork_andSyncLater(): Unit = runBlocking {
    db.disableNetwork().await()

    // confirm and remove don't suspend: they return without waiting for the server
    repository.confirm(uid, upcomingId)
    awaitRegistered(upcomingId, true)
    repository.confirm(uid, otherUpcomingId)
    repository.remove(uid, otherUpcomingId)
    awaitRegistered(otherUpcomingId, false)

    db.enableNetwork().await()
    awaitSynced()
    val saved = registrations().get(Source.SERVER).await()
    assertEquals(listOf(upcomingId), saved.documents.map { it.id })
  }

  @Test
  fun offline_removingASyncedConfirmation_deletesItOnTheServerLater(): Unit = runBlocking {
    repository.confirm(uid, upcomingId)
    awaitSynced()
    db.disableNetwork().await()

    repository.remove(uid, upcomingId)
    awaitRegistered(upcomingId, false)

    db.enableNetwork().await()
    awaitSynced()
    assertTrue(registrations().get(Source.SERVER).await().isEmpty)
  }

  @Test
  fun offline_confirmation_isRolledBack_whenTheEventStartsBeforeItSyncs(): Unit = runBlocking {
    db.disableNetwork().await()
    repository.confirm(uid, upcomingId)
    awaitRegistered(upcomingId, true)

    // The event starts while the phone is still offline
    EmulatorTestData.replaceRawDocument(
        "events",
        upcomingId,
        """{"title": {"stringValue": "Gentle yoga"}, """ +
            """"startAt": {"timestampValue": "${Instant.now().minus(Duration.ofMinutes(5))}"}}""",
    )

    db.enableNetwork().await()
    awaitRegistered(upcomingId, false)
    assertTrue(registrations().get(Source.SERVER).await().isEmpty)
  }

  @Test
  fun defaultConstructor_usesTheSharedInstance(): Unit = runBlocking {
    val shared = EventRegistrationRepositoryFirestore()
    shared.confirm(uid, upcomingId)
    awaitRegistered(upcomingId, true, shared)
  }

  @Test
  fun invalidIds_areRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      repository.observeIsRegistered(uid, "a/b")
    }
    assertThrows(IllegalArgumentException::class.java) { repository.confirm("", upcomingId) }
    assertThrows(IllegalArgumentException::class.java) { repository.remove(uid, " ") }
  }
}
