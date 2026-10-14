// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.carecircle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepositoryFirestore.Companion.COLLECTION
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests [CareCircleInviteRepositoryFirestore] against the Firestore emulator. Who can read and
 * write invites is tested in [CareCircleInviteSecurityRulesTest].
 */
@RunWith(AndroidJUnit4::class)
class CareCircleInviteRepositoryFirestoreTest {

  private lateinit var repository: CareCircleInviteRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  private val julie =
      CareCircleMemberDetails(
          firstName = "Julie",
          familyName = "Martin",
          relationship = Relationship.DAUGHTER,
          permissions = setOf(CarePermission.SYMPTOMS, CarePermission.PLANNING),
      )

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = CareCircleInviteRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  /** Alice's invite [inviteCode] on the server, or null if it doesn't exist. */
  private suspend fun serverDoc(inviteCode: String): DocumentSnapshot? =
      withTimeout(10_000) {
        db.waitForPendingWrites().await()
        // Among alice's invites: the rules refuse to get a missing invite
        db.collection(COLLECTION)
            .whereEqualTo("ownerUid", aliceUid)
            .get(Source.SERVER)
            .await()
            .documents
            .firstOrNull { it.id == inviteCode }
      }

  /**
   * Watches alice's [inviteCode] until it is seen, runs [change], and waits for the same collection
   * to emit null. Fails if the flow fails.
   */
  private suspend fun assertBecomesNull(inviteCode: String, change: suspend () -> Unit) =
      coroutineScope {
        val seen = CompletableDeferred<Unit>()
        val becameNull = async {
          repository
              .observeInvite(aliceUid, inviteCode)
              .onEach { if (it?.isSynced == true) seen.complete(Unit) }
              .dropWhile { !seen.isCompleted }
              .first { it == null }
        }
        withTimeout(10_000) { seen.await() }
        change()
        assertNull(withTimeout(10_000) { becameNull.await() })
      }

  private suspend fun create(details: CareCircleMemberDetails = julie, email: String? = null) =
      repository.createInvite(aliceUid, "Alice", details, email)

  @Test
  fun createInvite_returnsTheUnsyncedInvite_andSavesItOnTheServer(): Unit = runBlocking {
    val invite = create(email = "julie@example.com")
    assertTrue(CareCircleInvite.isWellFormedInviteCode(invite.inviteCode))
    assertEquals(aliceUid, invite.ownerUid)
    assertEquals(julie, invite.details)
    assertFalse(invite.isSynced)

    val doc = serverDoc(invite.inviteCode)!!
    assertEquals("Julie", doc.getString("firstName"))
    assertEquals("Alice", doc.getString("ownerFirstName"))
    assertEquals("julie@example.com", doc.getString("email"))
    // Stored in display order, whatever the order of the set
    assertEquals(listOf("PLANNING", "SYMPTOMS"), doc.get("permissions"))
    assertNotNull(doc.getTimestamp("createdAt"))
    assertTrue(doc.contains("redeemerUid"))
    assertNull(doc.get("redeemerUid"))
  }

  @Test
  fun observePendingInvites_emitsTheSyncedInviteWithItsServerTime(): Unit = runBlocking {
    val invite = create()
    val pending =
        withTimeout(10_000) {
          repository.observePendingInvites(aliceUid).first { list ->
            list.any { it.inviteCode == invite.inviteCode && it.isSynced }
          }
        }
    val observed = pending.single { it.inviteCode == invite.inviteCode }
    assertEquals(julie, observed.details)
    assertNotNull(observed.createdAt)
    assertNull(observed.redeemerUid)
  }

  @Test
  fun observePendingInvites_isSortedByName(): Unit = runBlocking {
    create(CareCircleMemberDetails("Marc"))
    create(CareCircleMemberDetails("anna"))
    create(CareCircleMemberDetails("Laura"))
    val names =
        withTimeout(10_000) { repository.observePendingInvites(aliceUid).first { it.size == 3 } }
            .map { it.details.firstName }
    assertEquals(listOf("anna", "Laura", "Marc"), names)
  }

  @Test
  fun observePendingInvites_skipsUsedAndMalformedInvites(): Unit = runBlocking {
    val pending = create()
    val used = CareCircleInvite.generateInviteCode()
    EmulatorTestData.createRawDocument(
        COLLECTION,
        used,
        """{"ownerUid": {"stringValue": "$aliceUid"}, "firstName": {"stringValue": "Marc"},
            "redeemerUid": {"stringValue": "marc"}}""",
    )
    val noName = CareCircleInvite.generateInviteCode()
    EmulatorTestData.createRawDocument(
        COLLECTION,
        noName,
        """{"ownerUid": {"stringValue": "$aliceUid"}, "redeemerUid": {"nullValue": null}}""",
    )
    db.waitForPendingWrites().await()
    // Loads the same query from the server first, so the listener's first emission isn't only
    // the cache
    db.collection(COLLECTION)
        .whereEqualTo("ownerUid", aliceUid)
        .whereEqualTo("redeemerUid", null)
        .get(Source.SERVER)
        .await()
    val codes =
        withTimeout(10_000) { repository.observePendingInvites(aliceUid).first() }
            .map { it.inviteCode }
    assertEquals(listOf(pending.inviteCode), codes)
  }

  @Test
  fun observePendingInvites_isSyncedOnlyOnceTheServerHasTheInvite(): Unit = runBlocking {
    db.disableNetwork().await()
    val invite = create()
    val offline =
        withTimeout(10_000) { repository.observePendingInvites(aliceUid).first { it.isNotEmpty() } }
    assertFalse(offline.single().isSynced)
    assertNull(offline.single().createdAt)

    db.enableNetwork().await()
    val online =
        withTimeout(10_000) {
          repository.observePendingInvites(aliceUid).first { list ->
            list.isNotEmpty() && list.all { it.isSynced }
          }
        }
    assertEquals(invite.inviteCode, online.single().inviteCode)
  }

  @Test
  fun observeInvite_emitsTheInvite_thenNullOnceRedeemed(): Unit = runBlocking {
    val invite = create()
    val observed =
        withTimeout(10_000) {
          repository.observeInvite(aliceUid, invite.inviteCode).first { it?.isSynced == true }
        }
    assertEquals(julie, observed!!.details)

    // bob redeems it on his phone, which deletes it, while alice keeps watching on hers
    assertBecomesNull(invite.inviteCode) {
      EmulatorTestData.deleteRawDocument("$COLLECTION/${invite.inviteCode}")
    }
  }

  @Test
  fun observeInvite_emitsNullOnceCancelled(): Unit = runBlocking {
    val invite = create()
    assertBecomesNull(invite.inviteCode) { repository.cancelInvite(invite.inviteCode) }
  }

  @Test
  fun observeInvite_ofAMissingInvite_isNull(): Unit = runBlocking {
    assertNull(
        withTimeout(10_000) {
          repository.observeInvite(aliceUid, CareCircleInvite.generateInviteCode()).first()
        }
    )
  }

  @Test
  fun updateInvite_replacesTheDetailsAndEmail(): Unit = runBlocking {
    val invite = create(email = "julie@example.com")
    val changed = CareCircleMemberDetails("Jules", null, Relationship.FRIEND, emptySet())
    repository.updateInvite(invite.inviteCode, changed, null)

    val doc = serverDoc(invite.inviteCode)!!
    assertEquals("Jules", doc.getString("firstName"))
    assertNull(doc.get("familyName"))
    assertEquals("FRIEND", doc.getString("relationship"))
    assertEquals(emptyList<String>(), doc.get("permissions"))
    assertNull(doc.get("email"))
    assertEquals("Alice", doc.getString("ownerFirstName"))
  }

  @Test
  fun updateInvite_ofACancelledInvite_doesNotRecreateIt(): Unit = runBlocking {
    val invite = create()
    repository.cancelInvite(invite.inviteCode)
    repository.updateInvite(invite.inviteCode, julie.copy(firstName = "Jules"), null)
    assertNull(serverDoc(invite.inviteCode))
  }

  @Test
  fun cancelInvite_deletesTheInvite(): Unit = runBlocking {
    val invite = create()
    repository.cancelInvite(invite.inviteCode)
    assertNull(serverDoc(invite.inviteCode))
    assertNull(
        withTimeout(10_000) { repository.observeInvite(aliceUid, invite.inviteCode).first() }
    )
  }

  @Test
  fun renewInvite_replacesTheInviteCode_andKeepsTheDetails(): Unit = runBlocking {
    val expired = create(email = "julie@example.com")
    val renewed = repository.renewInvite(expired)
    assertNotEquals(expired.inviteCode, renewed.inviteCode)
    assertEquals(expired.details, renewed.details)
    assertEquals("julie@example.com", renewed.email)
    assertFalse(renewed.isSynced)

    assertNull(serverDoc(expired.inviteCode))
    val doc = serverDoc(renewed.inviteCode)!!
    assertEquals("Julie", doc.getString("firstName"))
    assertNotNull(doc.getTimestamp("createdAt"))
  }

  @Test
  fun invalidDetailsOrEmail_areRefusedBeforeWriting(): Unit = runBlocking {
    val tooLong = "a".repeat(CareCircleMemberDetails.MAX_NAME_LENGTH + 1)
    assertRefused { create(CareCircleMemberDetails(" ")) }
    assertRefused { create(CareCircleMemberDetails(tooLong)) }
    assertRefused { create(email = "julie") }
    assertRefused { repository.updateInvite("ABCDEFGHJK", julie, "not an email") }
  }

  private suspend fun assertRefused(block: suspend () -> Unit) {
    try {
      block()
      throw AssertionError("Expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {}
  }
}
