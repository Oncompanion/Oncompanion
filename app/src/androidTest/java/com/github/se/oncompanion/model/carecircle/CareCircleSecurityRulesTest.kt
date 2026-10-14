// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
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
 * Tests firestore.rules for /users/{uid}/circle/{memberUid} through the client SDK, and the
 * patient's removal of a member's /users/{memberUid}/patients/{uid} link. Redeeming an invite is
 * tested in [CareCircleRedeemSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class CareCircleSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String
  private lateinit var memberUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    memberUid = EmulatorTestData.createUser("carol")
    bobUid = EmulatorTestData.createUser("bob")
    aliceUid = EmulatorTestData.createUser("alice") // alice, the patient, stays signed in
    // Carol joined alice's circle; written bypassing the rules, which only allow it by redeeming
    EmulatorTestData.createRawDocument(
        "users/$aliceUid/$COLLECTION",
        memberUid,
        """{"firstName": {"stringValue": "Carol"},
            "relationship": {"stringValue": "SISTER"},
            "permissions": {"arrayValue": {"values": [{"stringValue": "PLANNING"}]}},
            "email": {"stringValue": "carol@example.com"},
            "inviteCode": {"stringValue": "ABCDEFGHJK"},
            "addedAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
    )
    // Carol's links to alice's circle and to bob's
    for (patientUid in listOf(aliceUid, bobUid)) {
      EmulatorTestData.createRawDocument(
          "users/$memberUid/$PATIENTS",
          patientUid,
          """{"patientFirstName": {"stringValue": "Alice"},
              "inviteCode": {"stringValue": "ABCDEFGHJK"},
              "joinedAt": {"timestampValue": "2026-08-14T09:30:00Z"}}""",
      )
    }
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  private fun circle(uid: String): CollectionReference =
      db.collection("users").document(uid).collection(COLLECTION)

  private val member: DocumentReference
    get() = circle(aliceUid).document(memberUid)

  /** [caregiverUid]'s link to [patientUid]'s circle. */
  private fun joined(caregiverUid: String, patientUid: String): DocumentReference =
      db.collection("users").document(caregiverUid).collection(PATIENTS).document(patientUid)

  /** Valid details, as the patient saves them when editing a member. */
  private fun detailFields(vararg changes: Pair<String, Any?>) =
      mutableMapOf<String, Any?>(
              "firstName" to "Caroline",
              "familyName" to "Martin",
              "relationship" to "FRIEND",
              "permissions" to listOf("PLANNING", "SYMPTOMS"),
          )
          .apply { changes.forEach { (key, value) -> this[key] = value } }

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- read ----

  @Test
  fun ownerCanRead(): Unit = runBlocking {
    assertTrue(allowed(circle(aliceUid).document(memberUid).get(Source.SERVER)).exists())
    assertEquals(1, allowed(circle(aliceUid).get(Source.SERVER)).size())
  }

  @Test
  fun otherUserCannotRead(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(circle(aliceUid).document(memberUid).get(Source.SERVER))
    denied(circle(aliceUid).get(Source.SERVER))
  }

  @Test
  fun signedOutCannotRead(): Unit = runBlocking {
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(circle(aliceUid).get(Source.SERVER))
  }

  // ---- the owner edits and removes members ----

  @Test
  fun ownerCannotCreate(): Unit = runBlocking {
    denied(circle(aliceUid).document(bobUid).set(detailFields()))
    // Replacing the whole member doc would drop its email, invite code and join time
    denied(member.set(detailFields()))
  }

  @Test
  fun ownerUpdatesWhatTheyDecided(): Unit = runBlocking {
    allowed(member.update(detailFields()))
    allowed(member.update(detailFields("familyName" to null, "permissions" to emptyList<String>())))

    val saved = allowed(member.get(Source.SERVER))
    assertEquals("Caroline", saved.getString("firstName"))
    assertEquals("FRIEND", saved.getString("relationship"))
    assertEquals("carol@example.com", saved.getString("email"))
  }

  @Test
  fun ownerCannotChangeTheEmailInviteCodeOrJoinTime(): Unit = runBlocking {
    denied(member.update("email", "someone@example.com"))
    denied(member.update("email", FieldValue.delete()))
    denied(member.update("inviteCode", "ZZZZZZZZZZ"))
    denied(member.update("addedAt", FieldValue.serverTimestamp()))
    denied(member.update(detailFields("note" to "hi")))
  }

  @Test
  fun ownerCannotSaveInvalidDetails(): Unit = runBlocking {
    denied(member.update(detailFields("relationship" to "COUSIN")))
    denied(member.update(detailFields("firstName" to "  ")))
    denied(member.update(detailFields("firstName" to "a".repeat(51))))
    denied(member.update(detailFields("familyName" to "a".repeat(51))))
    denied(member.update(detailFields("permissions" to listOf("EDIT"))))
    denied(member.update(detailFields("permissions" to "PLANNING")))
  }

  @Test
  fun ownerRemovesTheMember_andTheirLinkToTheCircle(): Unit = runBlocking {
    allowed(db.batch().delete(member).delete(joined(memberUid, aliceUid)).commit())

    assertFalse(allowed(member.get(Source.SERVER)).exists())
    EmulatorTestData.signIn("carol")
    assertFalse(allowed(joined(memberUid, aliceUid).get(Source.SERVER)).exists())
    // Their link to another patient's circle stays
    assertTrue(allowed(joined(memberUid, bobUid).get(Source.SERVER)).exists())
  }

  @Test
  fun patientCannotTouchTheMembersOtherLinks(): Unit = runBlocking {
    denied(joined(memberUid, bobUid).delete())
    denied(joined(memberUid, aliceUid).update("patientFirstName", "Alicia"))
    denied(joined(memberUid, aliceUid).get(Source.SERVER))
  }

  @Test
  fun otherUserCannotWrite(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    denied(circle(aliceUid).document(bobUid).set(mapOf("firstName" to "Bob")))
    denied(member.update(detailFields()))
    denied(member.delete())
    denied(joined(memberUid, aliceUid).delete())
  }

  @Test
  fun memberCannotChangeTheirOwnDoc(): Unit = runBlocking {
    EmulatorTestData.signIn("carol")
    assertTrue(allowed(member.get(Source.SERVER)).exists())
    denied(member.update(detailFields()))
    denied(member.update("permissions", listOf("PLANNING", "EVENTS", "SYMPTOMS", "PRESCRIPTIONS")))
  }

  private companion object {
    const val PATIENTS = CareCircleInviteRepositoryFirestore.PATIENTS_COLLECTION
  }
}
