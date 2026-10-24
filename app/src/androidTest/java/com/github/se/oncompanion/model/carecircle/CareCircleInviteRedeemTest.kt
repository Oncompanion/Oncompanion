// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [CareCircleInviteRepositoryFirestore.redeem] against the Firestore emulator. Every case the
 * rules refuse is tested in [CareCircleRedeemSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class CareCircleInviteRedeemTest {

  private lateinit var repository: CareCircleInviteRepositoryFirestore
  private lateinit var aliceUid: String
  private lateinit var bobUid: String

  private val db
    get() = FirebaseEmulator.firestore

  private val julie =
      CareCircleMemberDetails(
          firstName = "Julie",
          familyName = "Martin",
          relationship = Relationship.DAUGHTER,
          permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
      )

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.createProfile("CAREGIVER")
    aliceUid = EmulatorTestData.createUser("alice") // alice, the patient, stays signed in
    repository = CareCircleInviteRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /** Alice invites julie and waits for the server, so the invite code works for others. */
  private suspend fun aliceInvite(): CareCircleInvite {
    val invite = repository.createInvite(aliceUid, "Alice", julie, "julie@example.com")
    withTimeout(10_000) { db.waitForPendingWrites().await() }
    return invite
  }

  /** Signs bob in and redeems [inviteCode] as him. */
  private suspend fun bobRedeems(inviteCode: String): RedeemResult {
    EmulatorTestData.signIn("bob")
    return withTimeout(20_000) { repository.redeem(inviteCode, bobUid, bobEmail()) }
  }

  private fun bobEmail(): String? = FirebaseEmulator.auth.currentUser?.email

  @Test
  fun redeem_joinsTheCircle_withTheInviteDetailsAndTheAccountEmail(): Unit = runBlocking {
    val invite = aliceInvite()
    // Typed in lower case with a dash
    val result = bobRedeems(invite.displayInviteCode.lowercase())
    assertEquals(RedeemResult.Joined(aliceUid, "Alice"), result)

    // A member can read their own member doc
    val member =
        withTimeout(10_000) {
          CareCircleRepositoryFirestore(db).observeMember(aliceUid, bobUid).first { it != null }
        }!!
    assertEquals(julie, member.details)
    assertEquals(bobEmail(), member.email)
    assertEquals(invite.inviteCode, member.inviteCode)
    assertNotNull(member.addedAt)

    val joined = joinedDoc().get(Source.SERVER).await()
    assertEquals(
        "Alice",
        joined.getString(CareCircleInviteRepositoryFirestore.FIELD_PATIENT_FIRST_NAME),
    )
    assertNotNull(joined.getTimestamp(CareCircleInviteRepositoryFirestore.FIELD_JOINED_AT))
    // Used up: gone from alice's invites (only she may look for a missing one)
    EmulatorTestData.signIn("alice")
    val aliceInvites =
        db.collection(CareCircleInviteRepositoryFirestore.COLLECTION)
            .whereEqualTo("ownerUid", aliceUid)
            .get(Source.SERVER)
            .await()
    assertTrue(aliceInvites.documents.none { it.id == invite.inviteCode })
  }

  @Test
  fun observeJoinedCircles_isEmptyBeforeJoining_thenListsThePatient(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.signIn("bob")
    assertEquals(
        emptyList<JoinedCareCircle>(),
        withTimeout(10_000) { repository.observeJoinedCircles(bobUid).first() },
    )
    assertFalse(withTimeout(10_000) { repository.hasJoinedCircle(bobUid) })

    bobRedeems(invite.inviteCode)

    val joined =
        withTimeout(10_000) { repository.observeJoinedCircles(bobUid).first { it.isNotEmpty() } }
    assertEquals(listOf(aliceUid), joined.map { it.patientUid })
    assertEquals("Alice", joined.single().patientFirstName)
    assertTrue(withTimeout(10_000) { repository.hasJoinedCircle(bobUid) })
  }

  @Test
  fun redeem_aUsedInviteCode_isInvalid(): Unit = runBlocking {
    val invite = aliceInvite()
    bobRedeems(invite.inviteCode)
    EmulatorTestData.createUser("carol")
    EmulatorTestData.createProfile("CAREGIVER")
    val carolUid = FirebaseEmulator.auth.currentUser!!.uid
    assertEquals(RedeemResult.InvalidOrUsed, repository.redeem(invite.inviteCode, carolUid, null))
  }

  @Test
  fun redeem_aSecondInviteFromTheSamePatient_isAlreadyMember(): Unit = runBlocking {
    val first = aliceInvite()
    EmulatorTestData.signIn("alice")
    val second = aliceInvite()
    assertEquals(RedeemResult.Joined(aliceUid, "Alice"), bobRedeems(first.inviteCode))
    assertEquals(RedeemResult.AlreadyMember("Alice"), bobRedeems(second.inviteCode))
  }

  @Test
  fun redeem_malformedUnknownOwnOrExpiredInviteCodes_areInvalid(): Unit = runBlocking {
    val own = aliceInvite()
    assertEquals(RedeemResult.InvalidOrUsed, repository.redeem(own.inviteCode, aliceUid, null))

    val expired = CareCircleInvite.generateInviteCode()
    val createdAt = Instant.now().minus(Duration.ofDays(8))
    EmulatorTestData.createRawDocument(
        CareCircleInviteRepositoryFirestore.COLLECTION,
        expired,
        """{"ownerUid": {"stringValue": "$aliceUid"}, "ownerFirstName": {"stringValue": "Alice"},
            "firstName": {"stringValue": "Julie"}, "relationship": {"stringValue": "DAUGHTER"},
            "permissions": {"arrayValue": {"values": []}},
            "createdAt": {"timestampValue": "$createdAt"}, "redeemerUid": {"nullValue": null}}""",
    )
    assertEquals(RedeemResult.InvalidOrUsed, bobRedeems(expired))
    val unknown = CareCircleInvite.generateInviteCode()
    assertEquals(RedeemResult.InvalidOrUsed, repository.redeem(unknown, bobUid, bobEmail()))
    // Too short, and with letters that are never used (0, O, 1, I, L)
    assertEquals(RedeemResult.InvalidOrUsed, repository.redeem("ABCDE", bobUid, bobEmail()))
    assertEquals(RedeemResult.InvalidOrUsed, repository.redeem("ABCDE-FGHJO", bobUid, bobEmail()))
  }

  @Test
  fun redeem_byAPatient_isRefusedByTheServer(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.createUser("dave")
    EmulatorTestData.createProfile("PATIENT")
    val daveUid = FirebaseEmulator.auth.currentUser!!.uid
    val result = withTimeout(20_000) { repository.redeem(invite.inviteCode, daveUid, null) }
    assertEquals(RedeemResult.InvalidOrUsed, result)
  }

  @Test
  fun redeem_offline_isOffline_andSavesNothing(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.signIn("bob")
    // disableNetwork() doesn't stop transactions, so this phone's Firestore points to a closed port
    val app =
        FirebaseApp.initializeApp(
            ApplicationProvider.getApplicationContext(),
            FirebaseApp.getInstance().options,
            "offline-${System.nanoTime()}",
        )
    // Terminated, not deleted: deleting a Firebase app breaks the emulator setup of later ones
    val offlineDb =
        FirebaseFirestore.getInstance(app).apply { useEmulator(FirebaseEmulator.HOST, 1) }
    try {
      val result =
          withTimeout(60_000) {
            CareCircleInviteRepositoryFirestore(offlineDb)
                .redeem(invite.inviteCode, bobUid, bobEmail())
          }
      assertEquals(RedeemResult.Offline, result)
      // Nothing on the device, so nothing is sent later either
      assertFalse(existsInCache(memberDoc(offlineDb)))
      assertFalse(existsInCache(joinedDoc(offlineDb)))
    } finally {
      offlineDb.terminate()
    }
    assertFalse(memberDoc().get(Source.SERVER).await().exists())
    assertFalse(joinedDoc().get(Source.SERVER).await().exists())
    assertTrue(inviteDoc(invite.inviteCode).get(Source.SERVER).await().exists())
  }

  private fun inviteDoc(inviteCode: String): DocumentReference =
      db.collection(CareCircleInviteRepositoryFirestore.COLLECTION).document(inviteCode)

  private fun memberDoc(db: FirebaseFirestore = this.db): DocumentReference =
      db.collection("users").document(aliceUid).collection("circle").document(bobUid)

  private fun joinedDoc(db: FirebaseFirestore = this.db): DocumentReference =
      db.collection("users")
          .document(bobUid)
          .collection(CareCircleInviteRepositoryFirestore.PATIENTS_COLLECTION)
          .document(aliceUid)

  /** Whether [doc] exists in the local cache; a document the cache never saw doesn't. */
  private suspend fun existsInCache(doc: DocumentReference): Boolean =
      try {
        doc.get(Source.CACHE).await().exists()
      } catch (e: FirebaseFirestoreException) {
        assertEquals(FirebaseFirestoreException.Code.UNAVAILABLE, e.code)
        false
      }
}
