package com.github.se.oncompanion.model.appointment

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies appointment storage and offline behavior through the emulator-connected client SDK. */
@RunWith(AndroidJUnit4::class)
class AppointmentRepositoryFirestoreTest {
  private lateinit var repository: AppointmentRepositoryFirestore
  private lateinit var uid: String

  private val db
    get() = FirebaseEmulator.firestore

  /** Starts each test with a fresh owner account and the existing emulator-connected instance. */
  @Before
  fun setUp(): Unit = runBlocking {
    db.enableNetwork().await()
    uid = EmulatorTestData.createUser("appointment-owner")
    repository = AppointmentRepositoryFirestore(db)
  }

  /** Restores networking after offline tests and releases the authenticated session. */
  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  private fun appointment(time: String = "2026-10-15T08:00:00Z") =
      Appointment(
          title = "Oncology consultation",
          scheduledAt = Instant.parse(time),
          type = AppointmentType.CONSULTATION,
          location = "HUG",
          notes = "Bring documents",
      )

  private fun collection() = db.collection("users").document(uid).collection("appointments")

  private suspend fun acknowledgeWrites() {
    withTimeout(10_000) { db.waitForPendingWrites().await() }
  }

  private suspend fun serverDocument(id: String): DocumentSnapshot =
      withTimeout(10_000) { collection().document(id).get(Source.SERVER).await() }

  private suspend fun storedAppointment(id: String): Appointment? =
      withTimeout(10_000) { repository.getAppointment(uid, id) }

  private fun assertContent(expected: Appointment, actual: Appointment?) {
    assertNotNull(actual)
    assertEquals(expected.copy(id = "", createdAt = null), actual!!.copy(id = "", createdAt = null))
  }

  private suspend fun rawAppointment(id: String, fields: JSONObject) {
    EmulatorTestData.createRawDocument("users/$uid/appointments", id, fields.toString())
  }

  private fun rawFields(): JSONObject =
      JSONObject(
          """{"title":{"stringValue":"Consultation"},
            "scheduledAt":{"timestampValue":"2026-10-15T08:00:00Z"},
            "type":{"stringValue":"CONSULTATION"},
            "createdAt":{"timestampValue":"2026-10-10T12:00:00Z"}}"""
      )

  /** Keeps the default instance constructor compatible with the emulator-connected SDK. */
  @Test
  fun defaultConstructorUsesTheSharedInstance(): Unit = runBlocking {
    val id = AppointmentRepositoryFirestore().addAppointment(uid, appointment())
    acknowledgeWrites()
    assertContent(appointment(), storedAppointment(id))
  }

  /** Preserves patient information while assigning fresh identity and server creation time. */
  @Test
  fun addAndGetRoundTripWithServerAssignedMetadata(): Unit = runBlocking {
    val input = appointment().copy(id = "ignored", createdAt = Instant.EPOCH)
    val id = repository.addAppointment(uid, input)
    assertTrue(id.isNotBlank())
    assertFalse(id == input.id)
    acknowledgeWrites()

    val saved = storedAppointment(id)
    assertContent(input, saved)
    assertEquals(id, saved!!.id)
    assertNotNull(saved.createdAt)
    assertNull(storedAppointment("ignored"))
  }

  /** Stores each recorded category and leaves optional patient text absent when not supplied. */
  @Test
  fun everyTypeRoundTripsWithNullOptionalFields(): Unit = runBlocking {
    for (type in AppointmentType.entries) {
      val input = appointment().copy(type = type, location = null, notes = null)
      val id = repository.addAppointment(uid, input)
      acknowledgeWrites()
      assertContent(input, storedAppointment(id))
    }
  }

  /** Confirms the persisted field names and types match the schema used by the rules. */
  @Test
  fun storedFieldsMatchTheDeclaredSchema(): Unit = runBlocking {
    val id = repository.addAppointment(uid, appointment())
    acknowledgeWrites()
    val document = serverDocument(id)
    assertEquals(
        setOf("title", "scheduledAt", "type", "location", "notes", "createdAt"),
        document.data!!.keys,
    )
    assertEquals("Oncology consultation", document.getString("title"))
    assertEquals(appointment().scheduledAt, document.getTimestamp("scheduledAt")!!.toInstant())
    assertEquals("CONSULTATION", document.getString("type"))
    assertEquals("HUG", document.getString("location"))
    assertEquals("Bring documents", document.getString("notes"))
    assertNotNull(document.getTimestamp("createdAt"))
  }

  /** Treats a server-confirmed absent document and an empty collection as ordinary empty states. */
  @Test
  fun missingAppointmentAndEmptyCollectionAreReported(): Unit = runBlocking {
    assertNull(storedAppointment("missing"))
    assertEquals(
        emptyList<Appointment>(),
        withTimeout(10_000) { repository.observeAppointments(uid).first() },
    )
  }

  /** Reads minimal valid stored data even when optional keys are omitted entirely. */
  @Test
  fun omittedOptionalFieldsLoadAsNull(): Unit = runBlocking {
    rawAppointment("minimal", rawFields())
    val saved = storedAppointment("minimal")
    assertNotNull(saved)
    assertNull(saved!!.location)
    assertNull(saved.notes)
  }

  /** Preserves recorded instants through daylight-saving transitions and subsecond precision. */
  @Test
  fun recordedTimesRoundTripWithoutTimeZoneConversion(): Unit = runBlocking {
    for (time in
        listOf("2026-03-29T00:30:00Z", "2026-10-25T01:30:00Z", "2026-10-15T08:00:00.123456Z")) {
      val input = appointment(time)
      val id = repository.addAppointment(uid, input)
      acknowledgeWrites()
      assertContent(input, storedAppointment(id))
    }
  }

  /** Orders equal-time records by their owning IDs, independently of editable titles. */
  @Test
  fun observationOrdersByScheduledTimeThenId(): Unit = runBlocking {
    rawAppointment(
        "later",
        rawFields().put("scheduledAt", JSONObject().put("timestampValue", "2026-10-16T08:00:00Z")),
    )
    rawAppointment("b", rawFields())
    rawAppointment("a", rawFields())
    // Populate the complete query cache before testing the observable ordering contract.
    withTimeout(10_000) { collection().orderBy("scheduledAt").get(Source.SERVER).await() }
    val entries = withTimeout(10_000) { repository.observeAppointments(uid).first { it.size == 3 } }
    assertEquals(listOf("a", "b", "later"), entries.map { it.id })
  }

  /** Makes new writes visible to an already collecting appointment screen. */
  @Test
  fun activeObservationReceivesAnAddedAppointment(): Unit = runBlocking {
    val emissions = Channel<List<Appointment>>(Channel.UNLIMITED)
    val observation = launch { repository.observeAppointments(uid).collect { emissions.send(it) } }
    try {
      withTimeout(10_000) {
        assertTrue(emissions.receive().isEmpty())
        val id = repository.addAppointment(uid, appointment())
        var entries = emissions.receive()
        while (entries.none { it.id == id }) entries = emissions.receive()
        assertContent(appointment(), entries.single())
      }
    } finally {
      observation.cancel()
    }
  }

  /** Rejects invalid appointments before a local or server document can be created. */
  @Test
  fun invalidInputWritesNothing(): Unit = runBlocking {
    val invalid =
        listOf(
            appointment().copy(title = " "),
            appointment().copy(title = "t".repeat(101)),
            appointment().copy(location = "l".repeat(201)),
            appointment().copy(notes = "n".repeat(1001)),
            appointment().copy(scheduledAt = Instant.MIN),
        )
    for (entry in invalid) {
      assertTrue(
          runCatching { repository.addAppointment(uid, entry) }.exceptionOrNull()
              is IllegalArgumentException
      )
    }
    assertTrue(withTimeout(10_000) { collection().get(Source.SERVER).await() }.isEmpty)
  }

  /** Skips malformed records without dropping the valid appointments in the same collection. */
  @Test
  fun malformedDocumentsAreSkipped(): Unit = runBlocking {
    val malformed =
        listOf(
            rawFields().put("type", JSONObject().put("stringValue", "UNKNOWN")),
            rawFields().apply { remove("title") },
            rawFields().put("title", JSONObject().put("stringValue", " ")),
            rawFields().put("title", JSONObject().put("integerValue", "42")),
            rawFields().apply { remove("scheduledAt") },
            rawFields().put("scheduledAt", JSONObject().put("stringValue", "2026-10-15")),
            rawFields().put("location", JSONObject().put("integerValue", "42")),
            rawFields().put("notes", JSONObject().put("stringValue", "n".repeat(1001))),
            rawFields().apply { remove("createdAt") },
            rawFields().put("createdAt", JSONObject().put("nullValue", JSONObject.NULL)),
        )
    for ((index, fields) in malformed.withIndex()) {
      val id = "malformed-$index"
      rawAppointment(id, fields)
      assertNull(storedAppointment(id))
    }
    val validId = repository.addAppointment(uid, appointment())
    acknowledgeWrites()
    withTimeout(10_000) { collection().orderBy("scheduledAt").get(Source.SERVER).await() }
    val entries =
        withTimeout(10_000) {
          repository.observeAppointments(uid).first { it.any { entry -> entry.id == validId } }
        }
    assertEquals(listOf(validId), entries.map { it.id })
  }

  /** Preserves read errors instead of presenting another patient's collection as empty. */
  @Test
  fun unauthorizedObservationFails(): Unit = runBlocking {
    val otherUid = EmulatorTestData.createUser("other-appointment-owner")
    EmulatorTestData.signIn("appointment-owner")
    val error =
        withTimeout(10_000) {
          runCatching { repository.observeAppointments(otherUid).collect {} }.exceptionOrNull()
        }
    assertTrue(error is FirebaseFirestoreException)
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (error as FirebaseFirestoreException).code,
    )
  }

  /** Keeps an unauthorized single read distinguishable from a missing appointment. */
  @Test
  fun unauthorizedSingleReadFails(): Unit = runBlocking {
    val otherUid = EmulatorTestData.createUser("other-appointment-owner")
    EmulatorTestData.signIn("appointment-owner")
    val error =
        withTimeout(10_000) {
          runCatching { repository.getAppointment(otherUid, "not-cached") }.exceptionOrNull()
        }
    assertTrue(error is FirebaseFirestoreException)
    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (error as FirebaseFirestoreException).code,
    )
  }

  /** Allows a write and its observation to complete while networking remains disabled. */
  @Test
  fun offlineAdditionIsVisibleBeforeReconnectingAndThenSynchronizes(): Unit = runBlocking {
    lateinit var id: String
    db.disableNetwork().await()
    try {
      id = withTimeout(5_000) { repository.addAppointment(uid, appointment()) }
      val entries =
          withTimeout(10_000) {
            repository.observeAppointments(uid).first { it.any { entry -> entry.id == id } }
          }
      val local = entries.single()
      assertContent(appointment(), local)
      assertNull(local.createdAt)
      assertContent(appointment(), storedAppointment(id))
      assertTrue(collection().document(id).get(Source.CACHE).await().metadata.hasPendingWrites())
    } finally {
      db.enableNetwork().await()
    }
    acknowledgeWrites()
    val saved = storedAppointment(id)
    assertContent(appointment(), saved)
    assertNotNull(saved!!.createdAt)
    assertFalse(serverDocument(id).metadata.hasPendingWrites())
  }

  /** Retains both single-record and collection reads for data cached during an online session. */
  @Test
  fun cachedAppointmentsRemainReadableOffline(): Unit = runBlocking {
    val id = repository.addAppointment(uid, appointment())
    acknowledgeWrites()
    withTimeout(10_000) { collection().orderBy("scheduledAt").get(Source.SERVER).await() }
    val online = storedAppointment(id)
    db.disableNetwork().await()
    try {
      assertEquals(online, storedAppointment(id))
      assertEquals(
          listOf(online),
          withTimeout(10_000) { repository.observeAppointments(uid).first() },
      )
    } finally {
      db.enableNetwork().await()
    }
  }

  /** Does not report an uncached offline document as an authoritative missing appointment. */
  @Test
  fun uncachedOfflineReadFails(): Unit = runBlocking {
    db.disableNetwork().await()
    try {
      val error =
          withTimeout(10_000) {
            runCatching { repository.getAppointment(uid, "never-cached") }.exceptionOrNull()
          }
      assertTrue(error is FirebaseFirestoreException)
      assertEquals(
          FirebaseFirestoreException.Code.UNAVAILABLE,
          (error as FirebaseFirestoreException).code,
      )
    } finally {
      db.enableNetwork().await()
    }
  }

  /** Exercises deferred server refusal while proving add does not wait for acknowledgement. */
  @Test
  fun rejectedWriteIsAbsentAfterReconnecting(): Unit = runBlocking {
    val otherUid = EmulatorTestData.createUser("other-appointment-owner")
    EmulatorTestData.signIn("appointment-owner")
    lateinit var id: String
    db.disableNetwork().await()
    try {
      id = withTimeout(5_000) { repository.addAppointment(otherUid, appointment()) }
      assertTrue(
          db.collection("users")
              .document(otherUid)
              .collection("appointments")
              .document(id)
              .get(Source.CACHE)
              .await()
              .exists()
      )
    } finally {
      db.enableNetwork().await()
    }
    acknowledgeWrites()
    EmulatorTestData.signIn("other-appointment-owner")
    val document = db.collection("users").document(otherUid).collection("appointments").document(id)
    assertFalse(withTimeout(10_000) { document.get(Source.SERVER).await() }.exists())
    assertFalse(document.get(Source.CACHE).await().exists())
  }
}
