// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryFirestore
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.model.event.EventRegistrationRepositoryFirestore
import com.github.se.oncompanion.model.event.EventRepositoryFirestore
import com.github.se.oncompanion.model.medication.MedicationRepositoryFirestore
import com.github.se.oncompanion.model.symptom.SymptomRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Tests that firestore.rules let a care-circle member read a patient's data only in the sections
 * ([CarePermission]) the patient shares with them, read only, and only while they are a member.
 * Each case runs for all four sections, on one document (`get`) and on the whole collection
 * (`list`). Reads use [Source.SERVER], so the local cache can't hide a denial.
 */
@RunWith(AndroidJUnit4::class)
class CareCircleSharedDataSecurityRulesTest {

  /**
   * One shared section: its collection under /users/{uid}, the document seeded in it, and a valid
   * new document and change, so a denied write is denied for who writes, not for what.
   */
  private class Section(
      val permission: CarePermission,
      val collection: String,
      val docId: String,
      val rawFields: String,
      val newDocId: String,
      val newDoc: () -> Map<String, Any>,
      val change: Pair<String, Any>,
  )

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String
  private lateinit var carolUid: String

  // Emulator data is shared between runs: each run uses its own Ligue events
  private val runId = UUID.randomUUID().toString()
  private val eventId = "shared-event-$runId"
  private val otherEventId = "shared-other-event-$runId"

  private val sections =
      listOf(
          Section(
              permission = CarePermission.PRESCRIPTIONS,
              collection = MedicationRepositoryFirestore.PRESCRIPTIONS,
              docId = "prescription-$runId",
              rawFields =
                  """{"prescribedOn": {"timestampValue": "2026-08-14T00:00:00Z"},
                      "createdAt": {"timestampValue": "2026-08-14T09:30:00Z"},
                      "medications": {"arrayValue": {"values": [{"mapValue": {"fields":
                          {"name": {"stringValue": "Tamoxifen"}}}}]}}}""",
              newDocId = "new-prescription-$runId",
              newDoc = {
                mapOf(
                    "prescribedOn" to Timestamp.now(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "medications" to listOf(mapOf("name" to "Tamoxifen")),
                )
              },
              change = "prescribedBy" to "Dr Martin",
          ),
          Section(
              permission = CarePermission.SYMPTOMS,
              collection = SymptomRepositoryFirestore.COLLECTION,
              docId = "symptom-$runId",
              rawFields =
                  """{"types": {"arrayValue": {"values": [{"stringValue": "FATIGUE"}]}},
                      "occurredAt": {"timestampValue": "2026-08-14T09:00:00Z"},
                      "createdAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
              newDocId = "new-symptom-$runId",
              newDoc = {
                mapOf(
                    "types" to listOf("NAUSEA"),
                    "occurredAt" to Timestamp.now(),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
              },
              change = "notes" to "Tired after the walk",
          ),
          Section(
              permission = CarePermission.PLANNING,
              collection = AppointmentRepositoryFirestore.COLLECTION,
              docId = "appointment-$runId",
              rawFields =
                  """{"title": {"stringValue": "Chemotherapy"},
                      "scheduledAt": {"timestampValue": "2026-12-01T09:00:00Z"},
                      "type": {"stringValue": "TREATMENT"},
                      "createdAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
              newDocId = "new-appointment-$runId",
              newDoc = {
                mapOf(
                    "title" to "Consultation",
                    "scheduledAt" to Timestamp.now(),
                    "type" to "CONSULTATION",
                    "createdAt" to FieldValue.serverTimestamp(),
                )
              },
              change = "title" to "Radiotherapy",
          ),
          Section(
              permission = CarePermission.EVENTS,
              collection = EventRegistrationRepositoryFirestore.COLLECTION,
              docId = eventId,
              rawFields = """{"confirmedAt": {"timestampValue": "${Instant.now()}"}}""",
              // Registrations are keyed by an upcoming event
              newDocId = otherEventId,
              newDoc = { mapOf("confirmedAt" to FieldValue.serverTimestamp()) },
              change = "confirmedAt" to FieldValue.serverTimestamp(),
          ),
      )

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    // Ligue events are added from the Firebase console, never by the app
    for (id in listOf(eventId, otherEventId)) {
      EmulatorTestData.createRawDocument(
          EventRepositoryFirestore.COLLECTION,
          id,
          """{"title": {"stringValue": "Gentle yoga"},
              "startAt": {"timestampValue": "${Instant.now().plus(Duration.ofDays(1))}"}}""",
      )
    }
    bobUid = EmulatorTestData.createUser("shared-bob")
    aliceUid = EmulatorTestData.createUser("shared-alice")
    carolUid = EmulatorTestData.createUser("shared-carol") // carol, the caregiver, stays signed in
    // Alice and bob each have one document in every section, written bypassing the rules
    for (uid in listOf(aliceUid, bobUid)) {
      for (section in sections) {
        EmulatorTestData.createRawDocument(
            "users/$uid/${section.collection}",
            section.docId,
            section.rawFields,
        )
      }
    }
    // Carol joined alice's circle, with nothing shared yet; only redeeming an invite creates it
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/$COLLECTION",
        carolUid,
        memberFields(emptySet()),
    )
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    EmulatorTestData.deleteRawDocument("${EventRepositoryFirestore.COLLECTION}/$eventId")
    EmulatorTestData.deleteRawDocument("${EventRepositoryFirestore.COLLECTION}/$otherEventId")
    EmulatorTestData.signOut()
  }

  /**
   * Carol's member doc in alice's circle, as the Firestore REST `fields` object. Null [permissions]
   * leaves the field out, like a malformed doc written from the console.
   */
  private fun memberFields(permissions: Set<CarePermission>?): String {
    val permissionsField =
        permissions?.let { granted ->
          val values = granted.joinToString(", ") { """{"stringValue": "${it.name}"}""" }
          """"permissions": {"arrayValue": {"values": [$values]}},"""
        } ?: ""
    return """{"firstName": {"stringValue": "Carol"},
               "relationship": {"stringValue": "SISTER"},
               $permissionsField
               "email": {"stringValue": "carol@example.com"},
               "inviteCode": {"stringValue": "ABCDEFGHJK"},
               "addedAt": {"timestampValue": "2026-08-14T09:30:00Z"}}"""
  }

  /** Alice changes what she shares with carol (from another device, so bypassing the rules). */
  private suspend fun shareWithCarol(permissions: Set<CarePermission>?) {
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/$COLLECTION",
        carolUid,
        memberFields(permissions),
    )
  }

  private fun collection(uid: String, section: Section): CollectionReference =
      db.collection("users").document(uid).collection(section.collection)

  private suspend fun <T> allowed(task: Task<T>): T =
      withTimeout(10_000.milliseconds) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000.milliseconds) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  /** The signed-in user reads [uid]'s document and whole collection in [section]. */
  private suspend fun assertCanRead(uid: String, section: Section) {
    val doc = allowed(collection(uid, section).document(section.docId).get(Source.SERVER))
    assertTrue("${section.permission} document", doc.exists())
    val ids = allowed(collection(uid, section).get(Source.SERVER)).documents.map { it.id }
    assertTrue("${section.permission} collection", section.docId in ids)
  }

  /** The signed-in user can read neither [uid]'s document nor collection in [section]. */
  private suspend fun assertCannotRead(uid: String, section: Section) {
    denied(collection(uid, section).document(section.docId).get(Source.SERVER))
    denied(collection(uid, section).get(Source.SERVER))
  }

  // ---- what the patient shares ----

  @Test
  fun memberReadsASharedSection(): Unit = runBlocking {
    for (section in sections) {
      shareWithCarol(setOf(section.permission))
      assertCanRead(aliceUid, section)
    }
  }

  @Test
  fun memberCannotReadSectionsNotShared(): Unit = runBlocking {
    for (section in sections) {
      shareWithCarol(CarePermission.entries.toSet() - section.permission)
      assertCannotRead(aliceUid, section)
    }
  }

  @Test
  fun memberWithNothingSharedReadsNothing(): Unit = runBlocking {
    for (section in sections) assertCannotRead(aliceUid, section)
  }

  // ---- losing access ----

  @Test
  fun permissionTurnedOff_stopsTheReads(): Unit = runBlocking {
    for (section in sections) {
      shareWithCarol(CarePermission.entries.toSet())
      assertCanRead(aliceUid, section)

      shareWithCarol(CarePermission.entries.toSet() - section.permission)
      assertCannotRead(aliceUid, section)
    }
  }

  @Test
  fun memberRemoved_readsNothing(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    for (section in sections) assertCanRead(aliceUid, section)

    EmulatorTestData.deleteRawDocument("users/$aliceUid/$COLLECTION/$carolUid")
    for (section in sections) assertCannotRead(aliceUid, section)
  }

  @Test
  fun memberDocWithoutPermissions_readsNothing(): Unit = runBlocking {
    shareWithCarol(null)
    for (section in sections) assertCannotRead(aliceUid, section)
  }

  @Test
  fun permissionTurnedOff_stopsAnOpenListener(): Unit = runBlocking {
    shareWithCarol(setOf(CarePermission.SYMPTOMS))
    val symptoms = sections.first { it.permission == CarePermission.SYMPTOMS }
    val newSymptomId = "after-revocation-$runId"
    val upToDate = CompletableDeferred<Unit>()
    val failure = CompletableDeferred<FirebaseFirestoreException>()
    val receivedNewSymptom = AtomicBoolean(false)
    // The journal as a caregiver's screen observes it, until the server stops it
    val registration =
        collection(aliceUid, symptoms).addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, e ->
          if (e != null) {
            failure.complete(e)
          } else if (snapshot != null) {
            if (snapshot.documents.any { it.id == newSymptomId }) receivedNewSymptom.set(true)
            if (!snapshot.metadata.isFromCache) upToDate.complete(Unit)
          }
        }
    try {
      withTimeout(10_000.milliseconds) { upToDate.await() }

      // Alice turns symptoms off, then logs a new one on another device
      shareWithCarol(emptySet())
      EmulatorTestData.createRawDocument(
          "users/$aliceUid/${symptoms.collection}",
          newSymptomId,
          symptoms.rawFields,
      )

      val error = withTimeout(10_000.milliseconds) { failure.await() }
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, error.code)
      assertFalse(receivedNewSymptom.get())
    } finally {
      registration.remove()
    }
  }

  // ---- read only ----

  @Test
  fun memberCannotWrite_evenInASharedSection(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    for (section in sections) {
      val aliceData = collection(aliceUid, section)
      denied(aliceData.document(section.newDocId).set(section.newDoc()))
      denied(aliceData.document(section.docId).update(section.change.first, section.change.second))
      denied(aliceData.document(section.docId).delete())
      assertCanRead(aliceUid, section)
    }
  }

  @Test
  fun ownerStillReadsAndWrites(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    EmulatorTestData.signIn("shared-alice")
    // The same writes as carol's: they are valid, so carol's are denied only for being hers
    for (section in sections) {
      val aliceData = collection(aliceUid, section)
      assertCanRead(aliceUid, section)
      allowed(aliceData.document(section.newDocId).set(section.newDoc()))
      allowed(aliceData.document(section.docId).update(section.change.first, section.change.second))
      allowed(aliceData.document(section.docId).delete())
    }
  }

  // ---- nothing beyond the shared sections ----

  @Test
  fun memberCannotReadThePatientsProfile(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    denied(db.collection("users").document(aliceUid).get(Source.SERVER))
  }

  @Test
  fun memberCannotReadAnotherPatientsData(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    for (section in sections) assertCannotRead(bobUid, section)
  }

  @Test
  fun strangerCannotRead(): Unit = runBlocking {
    shareWithCarol(CarePermission.entries.toSet())
    EmulatorTestData.signIn("shared-bob")
    for (section in sections) assertCannotRead(aliceUid, section)
  }
}
