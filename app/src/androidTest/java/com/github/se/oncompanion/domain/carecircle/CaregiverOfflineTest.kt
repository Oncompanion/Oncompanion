// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepositoryFirestore
import com.github.se.oncompanion.model.symptom.SymptomRepositoryProvider
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks on the Firebase emulators that a caregiver keeps what a patient shares with them while
 * offline (US-20): the access and the data already read come from the device, and the patient's
 * changes arrive once the connection is back. What the caregiver adds offline after losing the
 * right to is undone and reported once back online, so they are told it wasn't saved.
 */
@RunWith(AndroidJUnit4::class)
class CaregiverOfflineTest {

  private val db
    get() = FirebaseEmulator.firestore

  private val runId = UUID.randomUUID().toString()
  private val firstSymptom = "offline-first-$runId"
  private val laterSymptom = "offline-later-$runId"
  private lateinit var aliceUid: String
  private lateinit var carolUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("offline-alice")
    // Carol, the caregiver, stays signed in
    carolUid = EmulatorTestData.createUser("offline-carol")
    EmulatorTestData.createProfile("CAREGIVER", "Carol")
    // Alice logged a symptom and shares Symptoms with carol (from her own device)
    createSymptom(firstSymptom)
    aliceSharesSymptoms(canAdd = false)
  }

  /** Alice shares Symptoms with carol, and lets her add to them when [canAdd] (bypassing rules). */
  private suspend fun aliceSharesSymptoms(canAdd: Boolean) =
      EmulatorTestData.replaceRawDocument(
          "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
          carolUid,
          """{"firstName": {"stringValue": "Carol"},
              "relationship": {"stringValue": "SISTER"},
              "permissions": {"arrayValue": {"values": [{"stringValue": "SYMPTOMS"}]}},
              "canAdd": {"arrayValue": {"values": [${if (canAdd) """{"stringValue": "SYMPTOMS"}""" else ""}]}},
              "addedAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
      )

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    EmulatorTestData.signOut()
  }

  private suspend fun createSymptom(id: String) =
      EmulatorTestData.createRawDocument(
          "users/$aliceUid/${SymptomRepositoryFirestore.COLLECTION}",
          id,
          """{"types": {"arrayValue": {"values": [{"stringValue": "FATIGUE"}]}},
              "occurredAt": {"timestampValue": "2026-08-14T09:00:00Z"},
              "createdAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
      )

  /** Waits until carol may read alice's symptoms and sees the symptom [id] in them. */
  private suspend fun carolSees(id: String) {
    ResolveSectionAccess()(CarePermission.SYMPTOMS, aliceUid).first { it is SectionAccess.Readable }
    SymptomRepositoryProvider.repository.observeSymptoms(aliceUid).first { entries ->
      entries.any { it.id == id }
    }
  }

  @Test
  fun sharedSymptoms_stayReadableOffline_andRefreshOnceOnline(): Unit = runBlocking {
    // Read once online, so the access and the journal are on the device
    withTimeout(10.seconds) { carolSees(firstSymptom) }

    db.disableNetwork().await()
    // Offline: the same access and entries, from the device only
    withTimeout(5.seconds) { carolSees(firstSymptom) }

    // Alice logs another symptom meanwhile; carol gets it once back online
    createSymptom(laterSymptom)
    db.enableNetwork().await()
    withTimeout(10.seconds) { carolSees(laterSymptom) }
  }

  @Test
  fun symptomAddedOffline_afterTheRightToAddIsTakenAway_isUndoneAndReported(): Unit = runBlocking {
    aliceSharesSymptoms(canAdd = true)
    val rejectedWrites = RejectedWrites()
    val symptoms = SymptomRepositoryFirestore(db, rejectedWrites)
    withTimeout(10.seconds) { carolSees(firstSymptom) }

    // Carol logs a symptom for alice while offline: saved on her phone only
    db.disableNetwork().await()
    val added =
        withTimeout(5.seconds) {
          symptoms.addSymptom(
              aliceUid,
              SymptomEntry(
                  types = listOf(SymptomType.NAUSEA),
                  occurredAt = Instant.parse("2026-08-14T12:00:00Z"),
                  addedBy = carolUid,
                  addedByName = "Carol",
              ),
          )
        }

    // Meanwhile alice no longer lets her add; back online, the server refuses the symptom
    aliceSharesSymptoms(canAdd = false)
    db.enableNetwork().await()

    withTimeout(10.seconds) { rejectedWrites.unseen.first { it == 1 } }
    withTimeout(10.seconds) {
      symptoms.observeSymptoms(aliceUid).first { entries -> entries.none { it.id == added } }
    }
  }
}
