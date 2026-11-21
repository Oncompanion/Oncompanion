// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests that firestore.rules let only the patient record the doses they took each day (US-28), let
 * a caregiver who sees their prescriptions read them, and keep each count a whole number from 0 to
 * 24. alice is the patient, carol her caregiver, dave a stranger.
 */
@RunWith(AndroidJUnit4::class)
class TakenDosesSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var carolUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    EmulatorTestData.createUser("doses-dave")
    carolUid = EmulatorTestData.createUser("doses-carol")
    aliceUid = EmulatorTestData.createUser("doses-alice") // alice stays signed in
  }

  @After fun tearDown(): Unit = runBlocking { EmulatorTestData.signOut() }

  /** Alice shares [sections] with carol, who may also add and edit there (bypassing the rules). */
  private suspend fun shareWithCarol(vararg sections: String) {
    val list = sections.joinToString(", ", "[", "]") { """{"stringValue": "$it"}""" }
    EmulatorTestData.replaceRawDocument(
        "users/$aliceUid/${CareCircleRepositoryFirestore.COLLECTION}",
        carolUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": $list}},
            "canAdd": {"arrayValue": {"values": $list}},
            "canEditAndDelete": {"arrayValue": {"values": $list}},
            "inviteCode": {"stringValue": "ABCDEFGHJK"}}""",
    )
  }

  private fun days(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(TakenDosesRepositoryFirestore.COLLECTION)

  /** A valid day: [taken] counts by medication ID, with [extra] fields. */
  private fun day(
      taken: Map<String, Any?> = mapOf("med" to 2),
      extra: Map<String, Any?> = emptyMap(),
  ): Map<String, Any?> =
      mapOf("taken" to taken, "updatedAt" to FieldValue.serverTimestamp()) + extra

  /**
   * Runs [write] on a day of its own for each case, which the rules must deny: several refused
   * writes queued on one document can stall the SDK, and one refusal could hide another.
   */
  private suspend fun <T> eachDenied(cases: List<T>, write: (DocumentReference, T) -> Task<*>) =
      cases.forEachIndexed { index, case ->
        try {
          denied(write(days(aliceUid).document("2026-12-${index + 10}"), case))
        } catch (e: Throwable) {
          throw AssertionError("Not denied: $case", e)
        }
      }

  @Test
  fun patient_recordsAndChangesTheirDoses_andDeletesADay(): Unit = runBlocking {
    val today = days(aliceUid).document("2026-11-21")
    allowed(today.set(day()))
    allowed(today.set(day(mapOf("other" to 1)), SetOptions.merge()))
    allowed(today.update("taken.med", 0, "updatedAt", FieldValue.serverTimestamp()))
    allowed(today.get())
    allowed(today.delete())
    // Any real date
    allowed(days(aliceUid).document("2024-02-29").set(day()))
    allowed(days(aliceUid).document("1999-12-31").set(day()))
  }

  @Test
  fun eachCount_isAWholeNumberFrom0To24(): Unit = runBlocking {
    allowed(days(aliceUid).document("2026-11-21").set(day(mapOf("a" to 0, "b" to 24))))
    eachDenied(listOf<Any?>(-1, 25, 1.5, "2", true, null)) { doc, count ->
      doc.set(day(mapOf("med" to count)))
    }
  }

  @Test
  fun aDay_hasOnlyItsFields_andTheServersTime(): Unit = runBlocking {
    eachDenied(
        listOf(
            day(extra = mapOf("note" to "late")),
            mapOf("taken" to mapOf("med" to 1)),
            mapOf("updatedAt" to FieldValue.serverTimestamp()),
            day(extra = mapOf("updatedAt" to Timestamp.now())),
            mapOf("taken" to listOf(1), "updatedAt" to FieldValue.serverTimestamp()),
            day((1..101).associate { "m$it" to 1 }),
        )
    ) { doc, data ->
      doc.set(data)
    }
  }

  @Test
  fun aDay_isKeptUnderARealDate(): Unit = runBlocking {
    listOf(
            "today",
            "2026-11-21x",
            "21-11-2026",
            "2026-11-1",
            "2026-13-01",
            "2026-00-10",
            "2026-11-32",
        )
        .forEach { id ->
          try {
            denied(days(aliceUid).document(id).set(day()))
          } catch (e: Throwable) {
            throw AssertionError("Not denied: $id", e)
          }
        }
  }

  @Test
  fun aChange_mustSayWhenItWasMade(): Unit = runBlocking {
    val today = days(aliceUid).document("2026-11-21")
    allowed(today.set(day()))
    // A tick without the server's time
    denied(today.update("taken.med", 3))
  }

  @Test
  fun caregiverSeeingPrescriptions_readsButNeverRecords(): Unit = runBlocking {
    val today = days(aliceUid).document("2026-11-21")
    val yesterday = days(aliceUid).document("2026-11-20")
    allowed(today.set(day()))
    allowed(yesterday.set(day()))
    shareWithCarol("PRESCRIPTIONS")
    EmulatorTestData.signIn("doses-carol")

    allowed(today.get())
    allowed(days(aliceUid).get())
    // Even when the patient lets them add and edit prescriptions
    denied(today.set(day(mapOf("med" to 1)), SetOptions.merge()))
    denied(days(aliceUid).document("2026-11-22").set(day()))
    denied(yesterday.delete())
  }

  @Test
  fun caregiverNotSeeingPrescriptions_stranger_orSignedOut_cantReadOrWrite(): Unit = runBlocking {
    allowed(days(aliceUid).document("2026-11-21").set(day()))
    shareWithCarol("PLANNING", "SYMPTOMS")
    val users = listOf("doses-carol", "doses-dave", null)

    users.forEachIndexed { index, user ->
      if (user == null) EmulatorTestData.signOut() else EmulatorTestData.signIn(user)
      denied(days(aliceUid).document("2026-11-21").get())
      denied(days(aliceUid).document("2026-12-${index + 10}").set(day()))
    }
  }
}
