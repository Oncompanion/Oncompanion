// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.github.se.oncompanion.utils.allowed
import com.github.se.oncompanion.utils.denied
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Tests firestore.rules for /invites/{inviteCode} through the client SDK. */
@RunWith(AndroidJUnit4::class)
class CareCircleInviteSecurityRulesTest {

  private val db
    get() = FirebaseEmulator.firestore

  private lateinit var aliceUid: String
  private lateinit var bobUid: String

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    bobUid = EmulatorTestData.createUser("bob")
    aliceUid = EmulatorTestData.createUser("alice") // alice stays signed in
  }

  @After
  fun tearDown() {
    FirebaseEmulator.auth.signOut()
  }

  /** A new invite document with a fresh, well-formed invite code (emulator data is never wiped). */
  private fun newInvite(): DocumentReference =
      db.collection(COLLECTION).document(CareCircleInvite.generateInviteCode())

  /** The fields of a valid new invite from [owner], with [changes] applied (null removes a key). */
  private fun inviteFields(owner: String = aliceUid, vararg changes: Pair<String, Any?>) =
      mutableMapOf<String, Any?>(
              "ownerUid" to owner,
              "ownerFirstName" to "Alice",
              "firstName" to "Julie",
              "familyName" to "Martin",
              "relationship" to "DAUGHTER",
              "permissions" to listOf("PLANNING", "SYMPTOMS"),
              "email" to "julie@example.com",
              "createdAt" to FieldValue.serverTimestamp(),
              "redeemerUid" to null,
          )
          .apply { changes.forEach { (key, value) -> this[key] = value } }

  /** Creates a valid invite as alice and returns it. */
  private suspend fun aliceInvite(): DocumentReference =
      newInvite().also { allowed(it.set(inviteFields())) }

  // ---- allowed ----

  @Test
  fun ownerCanCreate_withOrWithoutOptionalFields(): Unit = runBlocking {
    aliceInvite()
    allowed(newInvite().set(inviteFields(aliceUid, "familyName" to null, "email" to null)))
    val minimal =
        inviteFields().apply {
          remove("familyName")
          remove("email")
        }
    allowed(newInvite().set(minimal))
  }

  @Test
  fun ownerCanListTheirOwnInvites(): Unit = runBlocking {
    aliceInvite()
    val own = db.collection(COLLECTION).whereEqualTo("ownerUid", aliceUid)
    assertEquals(1, allowed(own.get(Source.SERVER)).size())
    allowed(own.whereEqualTo("redeemerUid", null).get(Source.SERVER))
  }

  @Test
  fun ownerCanEditAnUnusedInvite(): Unit = runBlocking {
    val invite = aliceInvite()
    allowed(
        invite.update(
            mapOf(
                "firstName" to "Jules",
                "familyName" to null,
                "relationship" to "FRIEND",
                "permissions" to emptyList<String>(),
                "email" to null,
            )
        )
    )
  }

  @Test fun ownerCanDelete(): Unit = runBlocking { allowed(aliceInvite().delete()) }

  @Test
  fun anotherUserCanGetOneInviteByItsCode(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.signIn("bob")
    assertTrue(allowed(invite.get(Source.SERVER)).exists())
  }

  // ---- denied: create ----

  @Test
  fun cannotCreateForSomeoneElse(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(owner = bobUid)))
  }

  @Test
  fun cannotCreateWithAMalformedInviteCode(): Unit = runBlocking {
    listOf("ABCDEFGHJ0", "abcdefghjk", "ABCDEFGHJ", "ABCDEFGHJKM").forEach {
      denied(db.collection(COLLECTION).document(it).set(inviteFields()))
    }
  }

  @Test
  fun cannotChooseTheCreationTime(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(aliceUid, "createdAt" to Timestamp.now())))
  }

  @Test
  fun cannotCreateAnAlreadyUsedInvite(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(aliceUid, "redeemerUid" to bobUid)))
    denied(newInvite().set(inviteFields().apply { remove("redeemerUid") }))
  }

  @Test
  fun cannotCreateWithInvalidDetails(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(aliceUid, "permissions" to listOf("PLANNING", "EDIT"))))
    denied(newInvite().set(inviteFields(aliceUid, "relationship" to "COUSIN")))
    denied(newInvite().set(inviteFields(aliceUid, "firstName" to "a".repeat(51))))
    denied(newInvite().set(inviteFields(aliceUid, "familyName" to "a".repeat(51))))
    denied(newInvite().set(inviteFields(aliceUid, "firstName" to "  ")))
    denied(newInvite().set(inviteFields(aliceUid, "ownerFirstName" to "a".repeat(51))))
  }

  @Test
  fun cannotCreateWithATooLongEmail(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(aliceUid, "email" to "a".repeat(243) + "@example.com")))
  }

  @Test
  fun cannotCreateWithExtraFields(): Unit = runBlocking {
    denied(newInvite().set(inviteFields(aliceUid, "role" to "PATIENT")))
  }

  // ---- denied: other users ----

  @Test
  fun strangerCannotListSomeoneElsesInvites(): Unit = runBlocking {
    aliceInvite()
    EmulatorTestData.signIn("bob")
    denied(db.collection(COLLECTION).whereEqualTo("ownerUid", aliceUid).get(Source.SERVER))
    denied(db.collection(COLLECTION).get(Source.SERVER))
  }

  @Test
  fun strangerCannotUpdateOrDelete(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.signIn("bob")
    denied(invite.update("firstName", "Bob"))
    denied(invite.update("redeemerUid", bobUid))
    denied(invite.delete())
  }

  @Test
  fun signedOutCannotGet(): Unit = runBlocking {
    val invite = aliceInvite()
    EmulatorTestData.signOutAndWaitForFirestore(aliceUid)
    denied(invite.get(Source.SERVER))
  }

  // ---- denied: owner ----

  @Test
  fun ownerCannotChangeTheOwnerOrTheirName(): Unit = runBlocking {
    val invite = aliceInvite()
    denied(invite.update("ownerUid", bobUid))
    denied(invite.update("ownerFirstName", "Alicia"))
    denied(invite.update("createdAt", Timestamp.now()))
  }

  @Test
  fun ownerCannotMarkTheirInviteUsed(): Unit = runBlocking {
    denied(aliceInvite().update("redeemerUid", aliceUid))
  }

  @Test
  fun ownerCannotEditAUsedInvite(): Unit = runBlocking {
    val inviteCode = CareCircleInvite.generateInviteCode()
    // A used invite can only be written by redeeming it, so it is written bypassing the rules
    EmulatorTestData.createRawDocument(
        COLLECTION,
        inviteCode,
        """{"ownerUid": {"stringValue": "$aliceUid"}, "firstName": {"stringValue": "Julie"},
            "relationship": {"stringValue": "DAUGHTER"},
            "permissions": {"arrayValue": {"values": []}},
            "redeemerUid": {"stringValue": "$bobUid"}}""",
    )
    denied(db.collection(COLLECTION).document(inviteCode).update("firstName", "Jules"))
  }

  @Test
  fun ownerCannotEditWithInvalidDetails(): Unit = runBlocking {
    val invite = aliceInvite()
    denied(invite.update("firstName", ""))
    denied(invite.update("permissions", listOf("EDIT")))
    denied(invite.update("email", "a".repeat(255)))
  }
}
