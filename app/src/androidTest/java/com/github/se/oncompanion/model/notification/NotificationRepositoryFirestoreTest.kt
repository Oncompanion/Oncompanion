// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [NotificationRepositoryFirestore] on the Firestore emulator. The reading tests create
 * notifications with the security rules bypassed, to control every field; the notify tests write
 * them as a caregiver's phone does.
 */
@RunWith(AndroidJUnit4::class)
class NotificationRepositoryFirestoreTest {

  private lateinit var repository: NotificationRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  // Firestore keeps microseconds, the phone's clock may give nanoseconds: round so both agree
  private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = NotificationRepositoryFirestore(db, clock = { now })
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /**
   * Bob added prescription [itemId] for alice [age] ago, as the server would write it. [without]
   * leaves one field out, to write a document the app can't read.
   */
  private suspend fun serverWrites(
      itemId: String,
      age: Duration = Duration.ofMinutes(5),
      seen: Boolean = false,
      kind: String = "PRESCRIPTION_ADDED",
      without: String? = null,
  ) {
    val createdAt = now.minus(age)
    val fields =
        mapOf(
                "kind" to """{"stringValue": "$kind"}""",
                "itemId" to """{"stringValue": "$itemId"}""",
                "actorUid" to """{"stringValue": "bob-uid"}""",
                "actorFirstName" to """{"stringValue": "Bob"}""",
                "createdAt" to """{"timestampValue": "$createdAt"}""",
                "expiresAt" to """{"timestampValue": "${createdAt.plus(Duration.ofDays(7))}"}""",
                "seen" to """{"booleanValue": $seen}""",
            )
            .filterKeys { it != without }
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/notifications",
        "${kind}_$itemId",
        fields.entries.joinToString(", ", "{", "}") { (name, value) -> "\"$name\": $value" },
    )
  }

  private fun added(itemId: String, age: Duration = Duration.ofMinutes(5), seen: Boolean = false) =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = itemId,
          actorUid = "bob-uid",
          actorFirstName = "Bob",
          createdAt = now.minus(age),
          seen = seen,
      )

  private suspend fun <T> Flow<T>.awaitFirst(predicate: (T) -> Boolean = { true }): T =
      withTimeout(10.seconds) { first(predicate) }

  private suspend fun serverSeen(itemId: String): Boolean? =
      db.collection("users")
          .document(aliceUid)
          .collection("notifications")
          .document("PRESCRIPTION_ADDED_$itemId")
          .get(Source.SERVER)
          .await()
          .getBoolean("seen")

  @Test
  fun observeRecent_emitsAnEmptyListWhenThereAreNone(): Unit = runBlocking {
    assertEquals(emptyList<CircleNotification>(), repository.observeRecent(aliceUid).awaitFirst())
  }

  @Test
  fun observeRecent_readsEveryField_newestFirst(): Unit = runBlocking {
    serverWrites("older", age = Duration.ofDays(1), seen = true)
    serverWrites("newer", age = Duration.ofMinutes(5))

    val recent = repository.observeRecent(aliceUid).awaitFirst { it.size == 2 }

    assertEquals(
        listOf(added("newer"), added("older", age = Duration.ofDays(1), seen = true)),
        recent,
    )
    assertEquals("PRESCRIPTION_ADDED_newer", recent.first().id)
  }

  @Test
  fun observeRecent_leavesOutThoseOlderThanSevenDays(): Unit = runBlocking {
    serverWrites("six-days", age = Duration.ofDays(6))
    serverWrites("eight-days", age = Duration.ofDays(8))

    val recent = repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }

    assertEquals(listOf("six-days"), recent.map { it.itemId })
  }

  @Test
  fun observeRecent_emitsAgainWhenOneArrives(): Unit = runBlocking {
    val updates = repository.observeRecent(aliceUid)
    updates.awaitFirst()
    serverWrites("presc-1")
    assertEquals(listOf("presc-1"), updates.awaitFirst { it.isNotEmpty() }.map { it.itemId })
  }

  @Test
  fun observeRecent_skipsWhatItCantRead_andReadsAMissingSeenAsUnseen(): Unit = runBlocking {
    serverWrites("unknown-kind", kind = "SOMETHING_NEW")
    for (field in listOf("kind", "itemId", "actorUid", "actorFirstName")) {
      serverWrites("no-$field", without = field)
    }
    serverWrites("no-seen", without = "seen")

    val recent = repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }

    assertEquals(listOf(added("no-seen")), recent)
  }

  @Test
  fun observeRecent_ofSomeoneElse_failsInsteadOfEmitting(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")

    val error = runCatching { repository.observeRecent(bobUid).awaitFirst() }.exceptionOrNull()

    assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        (error as FirebaseFirestoreException).code,
    )
  }

  @Test
  fun observeRecent_offline_readsWhatTheDeviceAlreadyHas(): Unit = runBlocking {
    serverWrites("presc-1")
    repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }
    db.disableNetwork().await()

    val offline = repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }

    assertEquals(listOf("presc-1"), offline.map { it.itemId })
  }

  @Test
  fun markSeen_marksOnlyThoseGiven(): Unit = runBlocking {
    serverWrites("presc-1")
    serverWrites("presc-2")
    repository.observeRecent(aliceUid).awaitFirst { it.size == 2 }

    repository.markSeen(aliceUid, listOf("PRESCRIPTION_ADDED_presc-1"))

    val recent = repository.observeRecent(aliceUid).awaitFirst { list -> list.any { it.seen } }
    assertEquals(
        mapOf("presc-1" to true, "presc-2" to false),
        recent.associate { it.itemId to it.seen },
    )
    db.waitForPendingWrites().await()
    assertEquals(true, serverSeen("presc-1"))
    assertEquals(false, serverSeen("presc-2"))
  }

  @Test
  fun markSeen_offline_returnsAtOnce_andSyncsLater(): Unit = runBlocking {
    serverWrites("presc-1")
    repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }
    db.disableNetwork().await()

    withTimeout(2.seconds) { repository.markSeen(aliceUid, listOf("PRESCRIPTION_ADDED_presc-1")) }

    assertTrue(repository.observeRecent(aliceUid).awaitFirst { it.single().seen }.single().seen)
    db.enableNetwork().await()
    db.waitForPendingWrites().await()
    assertEquals(true, serverSeen("presc-1"))
  }

  @Test
  fun markSeen_oneThatNoLongerExists_doesntStopTheOthers(): Unit = runBlocking {
    serverWrites("presc-1")
    repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }

    repository.markSeen(
        aliceUid,
        listOf("PRESCRIPTION_ADDED_deleted", "PRESCRIPTION_ADDED_presc-1"),
    )

    // The server refuses the first update (no such document) and still applies the second
    withTimeout(10.seconds) { db.waitForPendingWrites().await() }
    assertEquals(true, serverSeen("presc-1"))
    assertFalse(
        db.collection("users")
            .document(aliceUid)
            .collection("notifications")
            .document("PRESCRIPTION_ADDED_deleted")
            .get(Source.SERVER)
            .await()
            .exists()
    )
  }

  // ---- notify: the caregiver's phone tells the patient, right after saving the prescription ----

  /** carol joins alice's circle, allowed to add prescriptions, and stays signed in. */
  private suspend fun carolMayAddAlicesPrescriptions(): String {
    val carolUid = EmulatorTestData.createUser("carol")
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": [{"stringValue": "PRESCRIPTIONS"}]}},
            "canAdd": {"arrayValue": {"values": [{"stringValue": "PRESCRIPTIONS"}]}},
            "inviteCode": {"stringValue": "ABCDEFGHJK"}}""",
    )
    return carolUid
  }

  /** carol saves prescription presc-1 for alice, waiting for her review (queued if offline). */
  private fun carolSavesAPrescription(carolUid: String) {
    db.collection("users")
        .document(aliceUid)
        .collection(MedicationRepositoryFirestore.PRESCRIPTIONS)
        .document("presc-1")
        .set(
            mapOf(
                "prescribedOn" to Timestamp.now(),
                "createdAt" to FieldValue.serverTimestamp(),
                "medications" to listOf(mapOf("id" to "m1", "name" to "Ondansetron 8 mg")),
                "addedBy" to carolUid,
                "addedByName" to "Carol",
                "confirmation" to "PENDING",
            )
        )
  }

  /** What carol's phone sends; its createdAt is ignored, the server sets it. */
  private fun fromCarol(carolUid: String, name: String = "Carol") =
      CircleNotification(
          kind = NotificationKind.PRESCRIPTION_ADDED,
          itemId = "presc-1",
          actorUid = carolUid,
          actorFirstName = name,
          createdAt = Instant.EPOCH,
      )

  private suspend fun awaitServerAck() =
      withTimeout(10.seconds) { db.waitForPendingWrites().await() }

  @Test
  fun notify_reachesThePatient_withTheServersTime_andExpiresSevenDaysLater(): Unit = runBlocking {
    val carolUid = carolMayAddAlicesPrescriptions()
    carolSavesAPrescription(carolUid)
    repository.notify(aliceUid, fromCarol(carolUid))
    awaitServerAck()

    EmulatorTestData.signIn("alice")
    val received = repository.observeRecent(aliceUid).awaitFirst { it.isNotEmpty() }.single()

    assertTrue(received.createdAt > Instant.EPOCH)
    assertEquals(fromCarol(carolUid).copy(createdAt = received.createdAt), received)
    val stored =
        db.collection("users")
            .document(aliceUid)
            .collection("notifications")
            .document(received.id)
            .get(Source.SERVER)
            .await()
    assertEquals(Timestamp(now.plus(Duration.ofDays(7))), stored.getTimestamp("expiresAt"))
  }

  @Test
  fun notify_offline_returnsAtOnce_andArrivesAfterThePrescription(): Unit = runBlocking {
    val carolUid = carolMayAddAlicesPrescriptions()
    db.disableNetwork().await()
    carolSavesAPrescription(carolUid)

    withTimeout(2.seconds) { repository.notify(aliceUid, fromCarol(carolUid)) }

    db.enableNetwork().await()
    awaitServerAck()
    EmulatorTestData.signIn("alice")
    assertEquals(false, serverSeen("presc-1"))
  }

  @Test
  fun notify_again_isRefusedQuietly_andCantMakeItUnseen(): Unit = runBlocking {
    val carolUid = carolMayAddAlicesPrescriptions()
    carolSavesAPrescription(carolUid)
    repository.notify(aliceUid, fromCarol(carolUid))
    awaitServerAck()
    EmulatorTestData.signIn("alice")
    repository.markSeen(aliceUid, listOf("PRESCRIPTION_ADDED_presc-1"))
    awaitServerAck()

    // carol's phone sends it again, e.g. after a retry: only alice may change it
    EmulatorTestData.signIn("carol")
    repository.notify(aliceUid, fromCarol(carolUid))
    awaitServerAck()

    EmulatorTestData.signIn("alice")
    assertEquals(true, serverSeen("presc-1"))
  }

  @Test
  fun notify_withANameTheRulesWouldRefuse_throws(): Unit = runBlocking {
    val carolUid = carolMayAddAlicesPrescriptions()
    for (name in listOf(" ", "a".repeat(CircleNotification.MAX_ACTOR_NAME_LENGTH + 1))) {
      val error = runCatching { repository.notify(aliceUid, fromCarol(carolUid, name)) }
      assertTrue(error.exceptionOrNull() is IllegalArgumentException)
    }
  }
}
