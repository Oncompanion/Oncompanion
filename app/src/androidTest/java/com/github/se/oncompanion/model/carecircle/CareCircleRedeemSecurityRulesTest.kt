// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
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
 * Tests firestore.rules for redeeming an invite through the client SDK: the write that deletes
 * `/invites/{inviteCode}` and creates `/users/{patientUid}/circle/{caregiverUid}` and
 * `/users/{caregiverUid}/patients/{patientUid}`.
 */
@RunWith(AndroidJUnit4::class)
class CareCircleRedeemSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String
  private lateinit var carolUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    carolUid = EmulatorTestData.createUser("carol")
    EmulatorTestData.createProfile("CAREGIVER")
    bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.createProfile("CAREGIVER")
    aliceUid = EmulatorTestData.createUser("alice") // alice, the patient, stays signed in
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  private fun invite(inviteCode: String): DocumentReference =
      db.collection(CareCircleInviteRepositoryFirestore.COLLECTION).document(inviteCode)

  private fun member(ownerUid: String, memberUid: String): DocumentReference =
      db.collection("users").document(ownerUid).collection("circle").document(memberUid)

  private fun joined(caregiverUid: String, patientUid: String): DocumentReference =
      db.collection("users").document(caregiverUid).collection("patients").document(patientUid)

  /** Creates a valid invite as the signed-in user, waits for the server and returns its code. */
  private suspend fun createInvite(): String {
    val inviteCode = CareCircleInvite.generateInviteCode()
    allowed(
        invite(inviteCode)
            .set(
                mapOf(
                    "ownerUid" to FirebaseEmulator.auth.currentUser!!.uid,
                    "ownerFirstName" to "Alice",
                    "firstName" to "Julie",
                    "familyName" to "Martin",
                    "relationship" to "DAUGHTER",
                    "permissions" to listOf("PLANNING", "SYMPTOMS"),
                    "email" to "julie@example.com",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "redeemerUid" to null,
                )
            )
    )
    return inviteCode
  }

  /** Alice creates an invite, then [caregiver] signs in. Returns the invite code. */
  private suspend fun aliceInviteThenSignIn(caregiver: String = "bob"): String {
    val inviteCode = createInvite()
    EmulatorTestData.signIn(caregiver)
    return inviteCode
  }

  /** The member doc the signed-in caregiver writes when redeeming, with [changes] applied. */
  private fun memberFields(inviteCode: String, vararg changes: Pair<String, Any?>) =
      mutableMapOf<String, Any?>(
              "firstName" to "Julie",
              "familyName" to "Martin",
              "relationship" to "DAUGHTER",
              "permissions" to listOf("PLANNING", "SYMPTOMS"),
              "email" to FirebaseEmulator.auth.currentUser!!.email,
              "inviteCode" to inviteCode,
              "addedAt" to FieldValue.serverTimestamp(),
          )
          .apply { changes.forEach { (key, value) -> this[key] = value } }

  private fun joinedFields(inviteCode: String, patientFirstName: String = "Alice") =
      mapOf(
          "patientFirstName" to patientFirstName,
          "inviteCode" to inviteCode,
          "joinedAt" to FieldValue.serverTimestamp(),
      )

  /**
   * The redeem writes of the signed-in caregiver for [ownerUid]'s [inviteCode], as one batch (the
   * rules check it like the repository's transaction); null fields leave that write out, so tests
   * can forge or skip each part.
   */
  private fun redeemBatch(
      inviteCode: String,
      member: Map<String, Any?>? = memberFields(inviteCode),
      deleteInvite: Boolean = true,
      joined: Map<String, Any?>? = joinedFields(inviteCode),
      ownerUid: String = aliceUid,
  ): Task<Void> {
    val uid = FirebaseEmulator.auth.currentUser!!.uid
    val batch = db.batch()
    member?.let { batch.set(member(ownerUid, uid), it) }
    if (deleteInvite) batch.delete(invite(inviteCode))
    joined?.let { batch.set(joined(uid, ownerUid), it) }
    return batch.commit()
  }

  /** Writes an invite from alice created 8 days ago, bypassing the rules. Returns its code. */
  private suspend fun expiredInvite(): String {
    val inviteCode = CareCircleInvite.generateInviteCode()
    val createdAt = Instant.now().minus(Duration.ofDays(8))
    EmulatorTestData.createRawDocument(
        CareCircleInviteRepositoryFirestore.COLLECTION,
        inviteCode,
        """{"ownerUid": {"stringValue": "$aliceUid"}, "ownerFirstName": {"stringValue": "Alice"},
            "firstName": {"stringValue": "Julie"}, "familyName": {"stringValue": "Martin"},
            "relationship": {"stringValue": "DAUGHTER"},
            "permissions": {"arrayValue": {"values": [{"stringValue": "PLANNING"},
                {"stringValue": "SYMPTOMS"}]}},
            "createdAt": {"timestampValue": "$createdAt"}, "redeemerUid": {"nullValue": null}}""",
    )
    return inviteCode
  }

  private suspend fun <T> allowed(task: Task<T>): T = withTimeout(10_000) { task.await() }

  private suspend fun denied(task: Task<*>) {
    try {
      withTimeout(10_000) { task.await() }
      fail("Expected PERMISSION_DENIED but the operation succeeded")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  // ---- allowed ----

  @Test
  fun caregiverRedeemsAValidInviteCode_whichDeletesIt(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))

    val memberDoc = allowed(member(aliceUid, bobUid).get(Source.SERVER))
    assertEquals("Julie", memberDoc.getString("firstName"))
    assertEquals(listOf("PLANNING", "SYMPTOMS"), memberDoc.get("permissions"))
    assertEquals(FirebaseEmulator.auth.currentUser!!.email, memberDoc.getString("email"))
    assertEquals(
        "Alice",
        allowed(joined(bobUid, aliceUid).get(Source.SERVER)).get("patientFirstName"),
    )
    // Used up: deleted, so it can't be read any more
    denied(invite(inviteCode).get(Source.SERVER))
  }

  @Test
  fun memberReadsTheirOwnCircleDoc_evenBeforeJoining(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    // What the redeem transaction reads to tell a new member from an existing one
    assertFalse(allowed(member(aliceUid, bobUid).get(Source.SERVER)).exists())
    allowed(redeemBatch(inviteCode))
    assertTrue(allowed(member(aliceUid, bobUid).get(Source.SERVER)).exists())
  }

  @Test
  fun patientReadsTheNewMember(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))
    EmulatorTestData.signIn("alice")
    assertTrue(allowed(member(aliceUid, bobUid).get(Source.SERVER)).exists())
  }

  @Test
  fun anyoneSignedInGetsAValidInvite_andThePatientTheirExpiredOne(): Unit = runBlocking {
    val valid = createInvite()
    val expired = expiredInvite()
    assertTrue(allowed(invite(expired).get(Source.SERVER)).exists())
    EmulatorTestData.signIn("bob")
    assertTrue(allowed(invite(valid).get(Source.SERVER)).exists())
  }

  @Test
  fun othersCannotTellUnknownUsedAndExpiredInviteCodesApart(): Unit = runBlocking {
    val used = createInvite()
    val expired = expiredInvite()
    EmulatorTestData.signIn("bob")
    allowed(redeemBatch(used))
    EmulatorTestData.signIn("carol")
    denied(invite(CareCircleInvite.generateInviteCode()).get(Source.SERVER))
    denied(invite(used).get(Source.SERVER))
    denied(invite(expired).get(Source.SERVER))
  }

  // ---- denied: using an invite code twice ----

  @Test
  fun sameCaregiverCannotRedeemTwice(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))
    denied(redeemBatch(inviteCode))
  }

  @Test
  fun secondCaregiverCannotJoinWithTheSameCode(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))
    EmulatorTestData.signIn("carol")
    denied(redeemBatch(inviteCode))
    denied(redeemBatch(inviteCode, deleteInvite = false))
  }

  @Test
  fun cannotJoinWithoutUsingUpTheInvite(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    // The member and patients docs, but the invite stays
    denied(redeemBatch(inviteCode, deleteInvite = false))
    denied(redeemBatch(inviteCode, deleteInvite = false, joined = null))
  }

  @Test
  fun cannotRejoinWithTheOldInviteCodeAfterRemoval(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))
    // What removing bob does; the invite was deleted when he joined
    EmulatorTestData.deleteRawDocument("users/$aliceUid/circle/$bobUid")
    EmulatorTestData.deleteRawDocument("users/$bobUid/patients/$aliceUid")
    denied(redeemBatch(inviteCode, deleteInvite = false))
    denied(redeemBatch(inviteCode))
  }

  // ---- denied: invalid invites or caregivers ----

  @Test
  fun cannotRedeemTheirOwnInvite(): Unit = runBlocking {
    EmulatorTestData.signIn("bob")
    val ownCode = createInvite()
    denied(redeemBatch(ownCode, ownerUid = bobUid))
  }

  @Test
  fun cannotRedeemAnExpiredInvite(): Unit = runBlocking {
    val inviteCode = expiredInvite()
    EmulatorTestData.signIn("bob")
    denied(redeemBatch(inviteCode))
  }

  @Test
  fun patientCannotRedeem(): Unit = runBlocking {
    val inviteCode = createInvite()
    EmulatorTestData.createUser("dave")
    EmulatorTestData.createProfile("PATIENT")
    denied(redeemBatch(inviteCode))
    EmulatorTestData.createUser("erin") // no profile at all
    denied(redeemBatch(inviteCode))
  }

  // ---- denied: forged or partial batches ----

  @Test
  fun cannotGrantThemselvesMoreThanTheInvite(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    val all = listOf("PLANNING", "EVENTS", "SYMPTOMS", "PRESCRIPTIONS")
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "permissions" to all)))
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "firstName" to "Jules")))
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "familyName" to null)))
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "role" to "PATIENT")))
  }

  @Test
  fun cannotForgeTheEmailOrJoinTime(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    val forged = "someone@example.com"
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "email" to forged)))
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "email" to null)))
    val now = Timestamp.now()
    denied(redeemBatch(inviteCode, member = memberFields(inviteCode, "addedAt" to now)))
  }

  @Test
  fun cannotSkipAPartOfTheBatch(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    denied(redeemBatch(inviteCode, member = null))
    denied(redeemBatch(inviteCode, joined = null))
    denied(redeemBatch(inviteCode, member = null, joined = null))
  }

  @Test
  fun caregiverCannotDeleteSomeoneElsesInvite_outsideARedeem(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    denied(invite(inviteCode).delete())
    // Nor with a member doc for another invite code
    val other = memberFields(inviteCode, "inviteCode" to CareCircleInvite.generateInviteCode())
    denied(redeemBatch(inviteCode, member = other))
  }

  @Test
  fun cannotForgeThePatientName(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    denied(redeemBatch(inviteCode, joined = joinedFields(inviteCode, patientFirstName = "Eve")))
  }

  @Test
  fun cannotJoinAnotherCircleWithTheInviteCode(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    // The invite is alice's, but the member doc is written in carol's circle
    denied(redeemBatch(inviteCode, ownerUid = carolUid))
  }

  @Test
  fun strangerCannotReadTheMemberOrWhatTheyJoined(): Unit = runBlocking {
    val inviteCode = aliceInviteThenSignIn()
    allowed(redeemBatch(inviteCode))
    EmulatorTestData.signIn("carol")
    denied(member(aliceUid, bobUid).get(Source.SERVER))
    denied(joined(bobUid, aliceUid).get(Source.SERVER))
  }
}
