// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.assertEachRejectionIsReported
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TakenDosesRepositoryFirestoreTest {

  private lateinit var repository: TakenDosesRepositoryFirestore
  private lateinit var rejectedWrites: RejectedWrites
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  private val today = LocalDate.of(2026, 11, 21)

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("doses-repo-alice")
    rejectedWrites = RejectedWrites()
    repository = TakenDosesRepositoryFirestore(db, rejectedWrites)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  private fun daysPath(uid: String) = "users/$uid/${TakenDosesRepositoryFirestore.COLLECTION}"

  private suspend fun dayOnce(date: LocalDate = today, uid: String = aliceUid) =
      withTimeout(10_000) { repository.observeDay(uid, date).first() }

  private suspend fun awaitServerAck() {
    withTimeout(10_000) { db.waitForPendingWrites().await() }
  }

  @Test
  fun theAppsRepository_savesInFirestore(): Unit = runBlocking {
    TakenDosesRepositoryProvider.repository.setTaken(aliceUid, today, "ondansetron", 1)
    assertEquals(mapOf("ondansetron" to 1), dayOnce())
  }

  @Test
  fun aDayWithoutTicks_isEmpty(): Unit = runBlocking {
    assertEquals(emptyMap<String, Int>(), dayOnce())
  }

  @Test
  fun ticks_readBackForTheirDay_andANewDayStartsEmpty(): Unit = runBlocking {
    repository.setTaken(aliceUid, today, "ondansetron", 2)
    awaitServerAck()

    assertEquals(mapOf("ondansetron" to 2), dayOnce())
    assertEquals(emptyMap<String, Int>(), dayOnce(today.plusDays(1)))
  }

  @Test
  fun tickingOneMedication_keepsTheOthers_andZeroUnticks(): Unit = runBlocking {
    repository.setTaken(aliceUid, today, "ondansetron", 2)
    repository.setTaken(aliceUid, today, "dexamethasone", 1)
    // Unticking writes the count itself, not "one less"
    repository.setTaken(aliceUid, today, "ondansetron", 0)
    awaitServerAck()

    assertEquals(mapOf("ondansetron" to 0, "dexamethasone" to 1), dayOnce())
  }

  @Test
  fun anOpenDay_showsANewTickAsItIsMade(): Unit = runBlocking {
    val updated =
        async(Dispatchers.IO) {
          withTimeout(10_000) {
            repository.observeDay(aliceUid, today).first { it == mapOf("ondansetron" to 1) }
          }
        }
    repository.setTaken(aliceUid, today, "ondansetron", 1)
    assertEquals(mapOf("ondansetron" to 1), updated.await())
  }

  @Test
  fun aCaregiverSeeingPrescriptions_readsThePatientsDay(): Unit = runBlocking {
    repository.setTaken(aliceUid, today, "ondansetron", 2)
    awaitServerAck()
    val carolUid = EmulatorTestData.createUser("doses-repo-carol")
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": [{"stringValue": "PRESCRIPTIONS"}]}},
            "canAdd": {"arrayValue": {"values": []}},
            "canEditAndDelete": {"arrayValue": {"values": []}},
            "inviteCode": {"stringValue": "ABCDEFGHJK"}}""",
    )

    assertEquals(mapOf("ondansetron" to 2), dayOnce())
  }

  @Test
  fun aDay_isStoredUnderItsDate_withACountPerMedication_andTheServersTime(): Unit = runBlocking {
    repository.setTaken(aliceUid, today, "ondansetron", 3)
    awaitServerAck()

    val doc =
        withTimeout(10_000) {
          db.collection(daysPath(aliceUid)).document("2026-11-21").get(Source.SERVER).await()
        }
    assertEquals(mapOf("ondansetron" to 3L), doc.get(TakenDosesRepositoryFirestore.FIELD_TAKEN))
    assertTrue(doc.get(TakenDosesRepositoryFirestore.FIELD_UPDATED_AT) is Timestamp)
    assertEquals(setOf("taken", "updatedAt"), doc.data!!.keys)
  }

  @Test
  fun invalidTick_throwsAndWritesNothing(): Unit = runBlocking {
    listOf("" to 1, "  " to 1, "ondansetron" to -1, "ondansetron" to 25).forEach { (id, count) ->
      try {
        repository.setTaken(aliceUid, today, id, count)
        fail("Expected an IllegalArgumentException for \"$id\", $count")
      } catch (expected: IllegalArgumentException) {
        // Nothing reaches Firestore
      }
    }
    assertEquals(emptyMap<String, Int>(), dayOnce())
  }

  @Test
  fun aCountANewerVersionCouldWrite_isLeftOut_andTheOthersStillShow(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        daysPath(aliceUid),
        "2026-11-21",
        """{"taken": {"mapValue": {"fields": {
              "kept": {"integerValue": "2"},
              "text": {"stringValue": "2"},
              "half": {"doubleValue": 1.5},
              "tooMany": {"integerValue": "30"},
              "negative": {"integerValue": "-1"}}}},
           "updatedAt": {"timestampValue": "2026-11-21T08:00:00Z"}}""",
    )
    EmulatorTestData.createRawDocument(
        daysPath(aliceUid),
        "2026-11-22",
        """{"taken": {"stringValue": "not a map"}}""",
    )

    assertEquals(mapOf("kept" to 2), dayOnce())
    assertEquals(emptyMap<String, Int>(), dayOnce(today.plusDays(1)))
  }

  @Test
  fun offlineTick_returnsAtOnce_showsRightAway_andSyncsWhenBackOnline(): Unit = runBlocking {
    db.disableNetwork().await()
    try {
      val observed =
          async(Dispatchers.IO) {
            withTimeout(10_000) { repository.observeDay(aliceUid, today).first { it.isNotEmpty() } }
          }
      val start = System.currentTimeMillis()
      withTimeout(5_000) { repository.setTaken(aliceUid, today, "ondansetron", 1) }
      val elapsed = System.currentTimeMillis() - start
      assertTrue("setTaken took $elapsed ms", elapsed < 1_000)
      assertEquals(mapOf("ondansetron" to 1), observed.await())
    } finally {
      db.enableNetwork().await()
    }

    awaitServerAck()
    val server =
        withTimeout(10_000) {
          db.collection(daysPath(aliceUid)).document("2026-11-21").get(Source.SERVER).await()
        }
    assertEquals(mapOf("ondansetron" to 1L), server.get(TakenDosesRepositoryFirestore.FIELD_TAKEN))
  }

  @Test
  fun readingAnotherUsersDoses_failsWithPermissionDenied(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("doses-repo-bob")
    EmulatorTestData.signIn("doses-repo-alice")
    try {
      dayOnce(uid = bobUid)
      fail("Expected PERMISSION_DENIED")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  @Test
  fun tickingForAnotherUser_returns_isRejectedByTheServer_andReported(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("doses-repo-bob")
    EmulatorTestData.signIn("doses-repo-alice")

    assertEachRejectionIsReported(
        db,
        rejectedWrites,
        listOf("a tick in bob's day" to { repository.setTaken(bobUid, today, "ondansetron", 1) }),
    )

    EmulatorTestData.signIn("doses-repo-bob")
    val server =
        withTimeout(10_000) {
          db.collection(daysPath(bobUid)).document("2026-11-21").get(Source.SERVER).await()
        }
    assertFalse("the rejected tick must not be on the server", server.exists())
  }
}
