package com.github.se.oncompanion.model.symptom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SymptomRepositoryFirestoreTest {

  private lateinit var repository: SymptomRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = SymptomRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  private fun fatigue(occurredAt: String = "2026-10-05T08:00:00Z") =
      SymptomEntry(
          type = SymptomType.FATIGUE,
          intensity = 7,
          occurredAt = Instant.parse(occurredAt),
          notes = "After the walk",
      )

  private fun hiccups() =
      SymptomEntry(
          type = SymptomType.OTHER,
          otherLabel = "Hiccups",
          intensity = 2,
          occurredAt = Instant.parse("2026-10-04T18:30:00Z"),
      )

  private fun assertSameContent(expected: SymptomEntry, actual: SymptomEntry?) {
    assertNotNull(actual)
    assertEquals(expected.copy(id = "", createdAt = null), actual!!.copy(id = "", createdAt = null))
  }

  private suspend fun awaitServerAck() {
    withTimeout(10_000) { db.waitForPendingWrites().await() }
  }

  private fun symptomsPath(uid: String) = "users/$uid/symptoms"

  private suspend fun observeOnce(id: String) =
      withTimeout(10_000) { repository.observeSymptom(aliceUid, id).first() }

  private suspend fun observeListOnce() =
      withTimeout(10_000) { repository.observeSymptoms(aliceUid).first() }

  /** Runs [block] and returns how long it took, in milliseconds. */
  private suspend fun timed(block: suspend () -> Unit): Long {
    val start = System.currentTimeMillis()
    block()
    return System.currentTimeMillis() - start
  }

  // ---- add / observe ----

  @Test
  fun defaultConstructorUsesSharedInstance(): Unit = runBlocking {
    val id = SymptomRepositoryFirestore().addSymptom(aliceUid, fatigue())
    assertSameContent(fatigue(), observeOnce(id))
  }

  @Test
  fun addThenObserveRoundTrips(): Unit = runBlocking {
    val id = repository.addSymptom(aliceUid, fatigue().copy(id = "ignored"))
    assertTrue(id.isNotBlank())
    assertTrue(id != "ignored")

    awaitServerAck()
    val stored = observeOnce(id)
    assertSameContent(fatigue(), stored)
    assertEquals(id, stored!!.id)
    assertNotNull("createdAt is set once the server confirms", stored.createdAt)
  }

  @Test
  fun otherWithLabelAndNoNotesRoundTrips(): Unit = runBlocking {
    val id = repository.addSymptom(aliceUid, hiccups())
    awaitServerAck()

    val stored = observeOnce(id)
    assertSameContent(hiccups(), stored)
    assertNull(stored!!.notes)
  }

  @Test
  fun fieldsAreWrittenWithTheExpectedTypes(): Unit = runBlocking {
    val id = repository.addSymptom(aliceUid, fatigue())
    awaitServerAck()

    val snapshot =
        withTimeout(10_000) {
          db.collection(symptomsPath(aliceUid)).document(id).get(Source.SERVER).await()
        }
    assertEquals("FATIGUE", snapshot.getString(SymptomRepositoryFirestore.FIELD_TYPE))
    assertEquals(7L, snapshot.getLong(SymptomRepositoryFirestore.FIELD_INTENSITY))
    assertEquals(
        Instant.parse("2026-10-05T08:00:00Z"),
        snapshot.getTimestamp(SymptomRepositoryFirestore.FIELD_OCCURRED_AT)!!.toInstant(),
    )
    assertEquals("After the walk", snapshot.getString(SymptomRepositoryFirestore.FIELD_NOTES))
    assertNull(snapshot.getString(SymptomRepositoryFirestore.FIELD_OTHER_LABEL))
    assertNotNull(snapshot.getTimestamp(SymptomRepositoryFirestore.FIELD_CREATED_AT))
  }

  @Test
  fun collectionAndFieldConstantsMatchSpec() {
    assertEquals("users", SymptomRepositoryFirestore.USERS_COLLECTION)
    assertEquals("symptoms", SymptomRepositoryFirestore.COLLECTION)
    assertEquals("type", SymptomRepositoryFirestore.FIELD_TYPE)
    assertEquals("otherLabel", SymptomRepositoryFirestore.FIELD_OTHER_LABEL)
    assertEquals("intensity", SymptomRepositoryFirestore.FIELD_INTENSITY)
    assertEquals("occurredAt", SymptomRepositoryFirestore.FIELD_OCCURRED_AT)
    assertEquals("notes", SymptomRepositoryFirestore.FIELD_NOTES)
    assertEquals("createdAt", SymptomRepositoryFirestore.FIELD_CREATED_AT)
  }

  @Test
  fun observeUnknownSymptomEmitsNull(): Unit = runBlocking { assertNull(observeOnce("missing")) }

  @Test
  fun observeSymptomsIsEmptyWithoutEntries(): Unit = runBlocking {
    assertEquals(emptyList<SymptomEntry>(), observeListOnce())
  }

  @Test
  fun observeSymptomsListsNewestOccurrenceFirst(): Unit = runBlocking {
    // Added in a different order than they occurred
    val middle = repository.addSymptom(aliceUid, fatigue("2026-10-03T08:00:00Z"))
    val newest = repository.addSymptom(aliceUid, fatigue("2026-10-05T08:00:00Z"))
    val oldest = repository.addSymptom(aliceUid, fatigue("2026-10-01T08:00:00Z"))
    awaitServerAck()

    assertEquals(listOf(newest, middle, oldest), observeListOnce().map { it.id })
  }

  @Test
  fun observeSymptomsEmitsNewEntries(): Unit = runBlocking {
    val emissions = Channel<List<SymptomEntry>>(Channel.UNLIMITED)
    val job =
        launch(Dispatchers.IO) {
          repository.observeSymptoms(aliceUid).collect { emissions.send(it) }
        }
    try {
      withTimeout(10_000) {
        assertEquals(emptyList<SymptomEntry>(), emissions.receive())

        val id = repository.addSymptom(aliceUid, fatigue())
        var current = emissions.receive()
        while (current.isEmpty()) current = emissions.receive()
        assertEquals(listOf(id), current.map { it.id })
      }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun observeSymptomsOfAnotherUserFails(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")
    assertPermissionDenied { repository.observeSymptoms(bobUid).first() }
  }

  @Test
  fun observeSymptomOfAnotherUserFails(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")
    assertPermissionDenied { repository.observeSymptom(bobUid, "any").first() }
  }

  /** Checks that [block] fails with PERMISSION_DENIED, not with a timeout or another error. */
  private suspend fun assertPermissionDenied(block: suspend () -> Unit) {
    try {
      withTimeout(10_000) { block() }
      fail("Expected PERMISSION_DENIED")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- server rejections: no exception, the entry is rolled back ----

  @Test
  fun addForAnotherUserReturnsButIsRejectedByServer(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")

    lateinit var id: String
    val elapsed = timed { withTimeout(5_000) { id = repository.addSymptom(bobUid, fatigue()) } }
    assertTrue("addSymptom took $elapsed ms", elapsed < 1_000)

    awaitServerAck()
    // The local write is rolled back
    val doc = db.collection(symptomsPath(bobUid)).document(id)
    withTimeout(10_000) { while (cachedExists(doc)) delay(100) }

    EmulatorTestData.signIn("bob")
    val server = withTimeout(10_000) { doc.get(Source.SERVER).await() }
    assertFalse("rejected symptom must not be on the server", server.exists())
  }

  /** Whether the local cache holds an existing document for [doc]. */
  private suspend fun cachedExists(doc: DocumentReference): Boolean =
      try {
        doc.get(Source.CACHE).await().exists()
      } catch (e: FirebaseFirestoreException) {
        false // UNAVAILABLE: nothing cached for this document
      }

  // ---- validation ----

  @Test
  fun addInvalidEntryThrowsAndWritesNothing(): Unit = runBlocking {
    val invalids =
        listOf(
            fatigue().copy(intensity = 0),
            fatigue().copy(intensity = 11),
            fatigue().copy(otherLabel = "Hiccups"),
            hiccups().copy(otherLabel = " "),
            fatigue().copy(notes = "n".repeat(1001)),
            fatigue().copy(occurredAt = Instant.now().plusSeconds(3600)),
        )
    for (invalid in invalids) {
      try {
        repository.addSymptom(aliceUid, invalid)
        fail("Expected IllegalArgumentException for $invalid")
      } catch (e: IllegalArgumentException) {
        // expected
      }
    }
    assertEquals(emptyList<SymptomEntry>(), observeListOnce())
  }

  // ---- malformed documents ----

  @Test
  fun malformedDocumentsAreSkipped(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        symptomsPath(aliceUid),
        "unknownType",
        """{"type": {"stringValue": "COUGH"}, "intensity": {"integerValue": "3"},
           "occurredAt": {"timestampValue": "2026-10-01T00:00:00Z"},
           "createdAt": {"timestampValue": "2026-10-01T00:00:00Z"}}""",
    )
    EmulatorTestData.createRawDocument(
        symptomsPath(aliceUid),
        "noIntensity",
        """{"type": {"stringValue": "PAIN"},
           "occurredAt": {"timestampValue": "2026-10-02T00:00:00Z"},
           "createdAt": {"timestampValue": "2026-10-02T00:00:00Z"}}""",
    )
    val valid = repository.addSymptom(aliceUid, fatigue())
    awaitServerAck()

    assertEquals(listOf(valid), observeListOnce().map { it.id })
    assertNull(observeOnce("unknownType"))
    assertNull(observeOnce("noIntensity"))
  }

  // ---- offline ----

  @Test
  fun offlineAddReturnsImmediatelyAndSyncsWhenBackOnline(): Unit = runBlocking {
    lateinit var id: String
    db.disableNetwork().await()
    try {
      val observed =
          async(Dispatchers.IO) {
            withTimeout(10_000) { repository.observeSymptoms(aliceUid).first { it.isNotEmpty() } }
          }
      val elapsed = timed { withTimeout(5_000) { id = repository.addSymptom(aliceUid, fatigue()) } }
      assertTrue("addSymptom took $elapsed ms", elapsed < 1_000)

      val offline = observed.await().single()
      assertSameContent(fatigue(), offline)
      assertEquals(id, offline.id)
      assertNull("createdAt is null until the server confirms", offline.createdAt)
    } finally {
      db.enableNetwork().await()
    }

    awaitServerAck()
    val synced = observeOnce(id)
    assertSameContent(fatigue(), synced)
    assertNotNull(synced!!.createdAt)
  }
}
