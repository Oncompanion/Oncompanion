package com.github.se.oncompanion.model.user

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserProfileRepositoryFirestoreTest {

  private lateinit var repository: UserProfileRepositoryFirestore
  private lateinit var aliceUid: String

  private val db
    get() = FirebaseEmulator.firestore

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    db.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = UserProfileRepositoryFirestore(db)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    db.enableNetwork().await()
    FirebaseEmulator.auth.signOut()
  }

  private fun alice() =
      UserProfile(
          uid = aliceUid,
          role = Role.PATIENT,
          firstName = "Alice",
          familyName = "Martin",
          cancerType = "Breast cancer",
      )

  private fun userDoc(uid: String): DocumentReference =
      db.collection(UserProfileRepositoryFirestore.COLLECTION).document(uid)

  private fun assertSameContent(expected: UserProfile, actual: UserProfile?) {
    assertNotNull(actual)
    assertEquals(expected.copy(createdAt = null), actual!!.copy(createdAt = null))
  }

  /** Waits until every pending write was acknowledged or rejected by the server. */
  private suspend fun awaitServerAck() {
    withTimeout(10_000) { db.waitForPendingWrites().await() }
  }

  /** Reads [uid]'s document from the server as the currently signed-in user. */
  private suspend fun serverSnapshot(uid: String): DocumentSnapshot =
      withTimeout(10_000) { userDoc(uid).get(Source.SERVER).await() }

  /** The cached document, or null if the local cache has no existing document for [uid]. */
  private suspend fun cachedSnapshot(uid: String): DocumentSnapshot? =
      try {
        userDoc(uid).get(Source.CACHE).await().takeIf { it.exists() }
      } catch (e: FirebaseFirestoreException) {
        null // UNAVAILABLE: nothing cached for this document
      }

  /** Polls the local cache until [predicate] holds for the cached document (null if absent). */
  private suspend fun awaitCache(uid: String, predicate: (DocumentSnapshot?) -> Boolean) {
    withTimeout(10_000) { while (!predicate(cachedSnapshot(uid))) delay(100) }
  }

  /** Runs [block] and returns how long it took, in milliseconds. */
  private suspend fun timed(block: suspend () -> Unit): Long {
    val start = System.currentTimeMillis()
    block()
    return System.currentTimeMillis() - start
  }

  // ---- create / get ----

  @Test
  fun defaultConstructorUsesSharedInstance(): Unit = runBlocking {
    val defaultRepository = UserProfileRepositoryFirestore()
    defaultRepository.createProfile(alice())
    assertSameContent(alice(), defaultRepository.getProfile(aliceUid))
  }

  @Test
  fun createThenGetRoundTrip(): Unit = runBlocking {
    repository.createProfile(alice())

    assertSameContent(alice(), repository.getProfile(aliceUid))

    awaitServerAck()
    val stored = repository.getProfile(aliceUid)
    assertSameContent(alice(), stored)
    assertNotNull("createdAt is set once the server confirms", stored!!.createdAt)
  }

  @Test
  fun createWithOnlyRequiredFieldsRoundTrips(): Unit = runBlocking {
    val minimal = UserProfile(uid = aliceUid, role = Role.CAREGIVER, firstName = "Al")
    repository.createProfile(minimal)
    awaitServerAck()

    val stored = repository.getProfile(aliceUid)
    assertSameContent(minimal, stored)
    assertNull(stored!!.familyName)
    assertNull(stored.cancerType)
  }

  @Test
  fun createIgnoresClientCreatedAt(): Unit = runBlocking {
    repository.createProfile(alice().copy(createdAt = Instant.EPOCH))
    awaitServerAck()

    val stored = repository.getProfile(aliceUid)
    assertNotNull(stored!!.createdAt)
    assertNotEquals(Instant.EPOCH, stored.createdAt)
    assertTrue(stored.createdAt!!.isAfter(Instant.parse("2020-01-01T00:00:00Z")))
  }

  @Test
  fun createdAtIsWrittenAsTimestampField(): Unit = runBlocking {
    repository.createProfile(alice())
    awaitServerAck()

    val snapshot = serverSnapshot(aliceUid)
    assertEquals("PATIENT", snapshot.getString(UserProfileRepositoryFirestore.FIELD_ROLE))
    assertEquals("Alice", snapshot.getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME))
    assertEquals("Martin", snapshot.getString(UserProfileRepositoryFirestore.FIELD_FAMILY_NAME))
    assertEquals(
        "Breast cancer",
        snapshot.getString(UserProfileRepositoryFirestore.FIELD_CANCER_TYPE),
    )
    assertNotNull(snapshot.getTimestamp(UserProfileRepositoryFirestore.FIELD_CREATED_AT))
  }

  @Test
  fun collectionAndFieldConstantsMatchSpec() {
    assertEquals("users", UserProfileRepositoryFirestore.COLLECTION)
    assertEquals("role", UserProfileRepositoryFirestore.FIELD_ROLE)
    assertEquals("firstName", UserProfileRepositoryFirestore.FIELD_FIRST_NAME)
    assertEquals("familyName", UserProfileRepositoryFirestore.FIELD_FAMILY_NAME)
    assertEquals("cancerType", UserProfileRepositoryFirestore.FIELD_CANCER_TYPE)
    assertEquals("createdAt", UserProfileRepositoryFirestore.FIELD_CREATED_AT)
  }

  @Test
  fun getUnknownProfileReturnsNull(): Unit = runBlocking {
    assertNull(repository.getProfile(aliceUid))
  }

  // ---- update ----

  @Test
  fun updateChangesEditableFieldsAndKeepsRoleAndCreatedAt(): Unit = runBlocking {
    repository.createProfile(alice())
    awaitServerAck()
    val createdAt = repository.getProfile(aliceUid)!!.createdAt
    assertNotNull(createdAt)

    repository.updateProfile(
        alice()
            .copy(
                role = Role.CAREGIVER,
                firstName = "Alicia",
                familyName = "Dupont",
                cancerType = "Lymphoma",
                createdAt = Instant.EPOCH,
            )
    )
    val expected =
        alice().copy(firstName = "Alicia", familyName = "Dupont", cancerType = "Lymphoma")

    // Applied locally right away...
    assertSameContent(expected, repository.getProfile(aliceUid))

    // ...and accepted by the server: the role passed to update is ignored.
    awaitServerAck()
    val stored = repository.getProfile(aliceUid)
    assertSameContent(expected, stored)
    assertEquals(Role.PATIENT, stored!!.role)
    assertEquals(createdAt, stored.createdAt)

    val server = serverSnapshot(aliceUid)
    assertEquals("PATIENT", server.getString(UserProfileRepositoryFirestore.FIELD_ROLE))
    assertEquals("Alicia", server.getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME))
    assertEquals(
        createdAt,
        server.getTimestamp(UserProfileRepositoryFirestore.FIELD_CREATED_AT)!!.toDate().toInstant(),
    )
  }

  @Test
  fun updateIgnoresRoleForCaregiverToo(): Unit = runBlocking {
    repository.createProfile(alice().copy(role = Role.CAREGIVER))

    repository.updateProfile(alice().copy(role = Role.PATIENT, firstName = "Alicia"))
    awaitServerAck()

    val server = serverSnapshot(aliceUid)
    assertEquals("CAREGIVER", server.getString(UserProfileRepositoryFirestore.FIELD_ROLE))
    assertEquals("Alicia", server.getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME))
    assertEquals(Role.CAREGIVER, repository.getProfile(aliceUid)!!.role)
  }

  @Test
  fun updateCanClearOptionalFields(): Unit = runBlocking {
    repository.createProfile(alice())

    repository.updateProfile(alice().copy(familyName = null, cancerType = null))
    awaitServerAck()

    val stored = repository.getProfile(aliceUid)
    assertNotNull(stored)
    assertNull(stored!!.familyName)
    assertNull(stored.cancerType)
    assertEquals("Alice", stored.firstName)
    val server = serverSnapshot(aliceUid)
    assertNull(server.getString(UserProfileRepositoryFirestore.FIELD_FAMILY_NAME))
    assertNull(server.getString(UserProfileRepositoryFirestore.FIELD_CANCER_TYPE))
  }

  @Test
  fun updateWithoutExistingProfileCreatesNothing(): Unit = runBlocking {
    repository.updateProfile(alice())

    awaitServerAck()
    delay(500)
    assertFalse("no document on the server", serverSnapshot(aliceUid).exists())
    assertNull("no document in the local cache", cachedSnapshot(aliceUid))
    assertNull(repository.getProfile(aliceUid))
  }

  // ---- observe ----

  @Test
  fun observeProfileEmitsNullThenCreatedThenUpdated(): Unit = runBlocking {
    val emissions = Channel<UserProfile?>(Channel.UNLIMITED)
    val job =
        launch(Dispatchers.IO) {
          repository.observeProfile(aliceUid).collect { emissions.send(it) }
        }
    try {
      withTimeout(10_000) {
        assertNull("first emission is the current (missing) value", emissions.receive())

        repository.createProfile(alice())
        var current = emissions.receive()
        while (current == null) current = emissions.receive()
        assertSameContent(alice(), current)

        repository.updateProfile(alice().copy(firstName = "Alicia", role = Role.CAREGIVER))
        while (current?.firstName != "Alicia") current = emissions.receive()
        assertSameContent(alice().copy(firstName = "Alicia"), current)
      }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun observeProfileEmitsExistingProfileFirst(): Unit = runBlocking {
    repository.createProfile(alice())

    val first = withTimeout(10_000) { repository.observeProfile(aliceUid).first() }

    assertSameContent(alice(), first)
  }

  // ---- validation ----

  @Test
  fun createInvalidProfileThrowsAndWritesNothing(): Unit = runBlocking {
    val invalid = alice().copy(firstName = "  ")
    try {
      repository.createProfile(invalid)
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
    assertNull(repository.getProfile(aliceUid))
    assertFalse(serverSnapshot(aliceUid).exists())
  }

  @Test
  fun createWithTooLongFieldsThrows(): Unit = runBlocking {
    val invalids =
        listOf(
            alice().copy(firstName = "a".repeat(51)),
            alice().copy(familyName = "b".repeat(51)),
            alice().copy(cancerType = "c".repeat(101)),
            alice().copy(uid = ""),
        )
    for (invalid in invalids) {
      try {
        repository.createProfile(invalid)
        fail("Expected IllegalArgumentException for $invalid")
      } catch (e: IllegalArgumentException) {
        // expected
      }
    }
    assertNull(repository.getProfile(aliceUid))
  }

  @Test
  fun updateInvalidProfileThrowsAndKeepsStoredProfile(): Unit = runBlocking {
    repository.createProfile(alice())

    try {
      repository.updateProfile(alice().copy(firstName = "a".repeat(51)))
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }

    assertSameContent(alice(), repository.getProfile(aliceUid))
    awaitServerAck()
    assertEquals(
        "Alice",
        serverSnapshot(aliceUid).getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME),
    )
  }

  // ---- server rejections: no exception, the change is rolled back ----

  @Test
  fun createForAnotherUserReturnsButIsRejectedByServer(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")

    val elapsed = timed {
      withTimeout(5_000) { repository.createProfile(alice().copy(uid = bobUid)) }
    }
    assertTrue("createProfile took $elapsed ms", elapsed < 1_000)

    awaitServerAck()
    awaitCache(bobUid) { it == null } // local write rolled back

    EmulatorTestData.signIn("bob")
    assertFalse("rejected profile must not be on the server", serverSnapshot(bobUid).exists())
  }

  @Test
  fun updateOfAnotherUsersProfileReturnsButIsRejectedByServer(): Unit = runBlocking {
    repository.createProfile(alice())
    awaitServerAck()
    EmulatorTestData.createUser("bob") // bob stays signed in

    withTimeout(5_000) { repository.updateProfile(alice().copy(firstName = "Hacked")) }
    awaitServerAck()

    EmulatorTestData.signIn("alice")
    assertEquals(
        "Alice",
        serverSnapshot(aliceUid).getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME),
    )
  }

  @Test
  fun rejectedOwnUpdateReturnsAndIsRolledBackLocally(): Unit = runBlocking {
    // A stored document the rules no longer accept (unknown key): any update of it is denied.
    EmulatorTestData.createRawDocument(
        "users",
        aliceUid,
        """{"role": {"stringValue": "PATIENT"}, "firstName": {"stringValue": "Alice"},
           "isAdmin": {"booleanValue": true},
           "createdAt": {"timestampValue": "2024-01-01T00:00:00Z"}}""",
    )
    serverSnapshot(aliceUid) // load it into the local cache

    val elapsed = timed {
      withTimeout(5_000) { repository.updateProfile(alice().copy(firstName = "Alicia")) }
    }
    assertTrue("updateProfile took $elapsed ms", elapsed < 1_000)

    awaitServerAck()
    awaitCache(aliceUid) {
      it?.getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME) == "Alice"
    }
    assertEquals(
        "Alice",
        serverSnapshot(aliceUid).getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME),
    )
  }

  // ---- malformed documents ----

  @Test
  fun documentWithUnknownRoleIsReadAsNull(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users",
        aliceUid,
        """{"role": {"stringValue": "ADMIN"}, "firstName": {"stringValue": "Alice"},
           "createdAt": {"timestampValue": "2024-01-01T00:00:00Z"}}""",
    )

    assertNull(repository.getProfile(aliceUid))
    assertNull(withTimeout(10_000) { repository.observeProfile(aliceUid).first() })
  }

  @Test
  fun documentWithMissingFirstNameIsReadAsNull(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users",
        aliceUid,
        """{"role": {"stringValue": "PATIENT"},
           "createdAt": {"timestampValue": "2024-01-01T00:00:00Z"}}""",
    )

    assertNull(repository.getProfile(aliceUid))
  }

  @Test
  fun documentWithMissingRoleIsReadAsNull(): Unit = runBlocking {
    EmulatorTestData.createRawDocument(
        "users",
        aliceUid,
        """{"firstName": {"stringValue": "Alice"},
           "createdAt": {"timestampValue": "2024-01-01T00:00:00Z"}}""",
    )

    assertNull(repository.getProfile(aliceUid))
  }

  // ---- offline ----

  @Test
  fun offlineWritesReturnImmediatelyAndSyncWhenBackOnline(): Unit = runBlocking {
    db.disableNetwork().await()
    try {
      val createMs = timed { withTimeout(5_000) { repository.createProfile(alice()) } }
      assertTrue("createProfile took $createMs ms", createMs < 1_000)

      val cached = repository.getProfile(aliceUid)
      assertSameContent(alice(), cached)
      assertNull("createdAt is null until the server confirms", cached!!.createdAt)

      val updateMs = timed {
        withTimeout(5_000) {
          repository.updateProfile(alice().copy(firstName = "Ali", role = Role.CAREGIVER))
        }
      }
      assertTrue("updateProfile took $updateMs ms", updateMs < 1_000)
      val updated = repository.getProfile(aliceUid)
      assertEquals("Ali", updated?.firstName)
      assertEquals(Role.PATIENT, updated?.role)
    } finally {
      db.enableNetwork().await()
    }

    // Once back online the pending writes sync and the server sets createdAt.
    awaitServerAck()
    val synced = repository.getProfile(aliceUid)
    assertEquals("Ali", synced?.firstName)
    assertEquals(Role.PATIENT, synced?.role)
    assertNotNull(synced!!.createdAt)
    val server = serverSnapshot(aliceUid)
    assertEquals("Ali", server.getString(UserProfileRepositoryFirestore.FIELD_FIRST_NAME))
    assertEquals("PATIENT", server.getString(UserProfileRepositoryFirestore.FIELD_ROLE))
  }

  @Test
  fun offlineGetReadsSyncedProfileFromCache(): Unit = runBlocking {
    repository.createProfile(alice())
    awaitServerAck()
    val online = repository.getProfile(aliceUid)

    db.disableNetwork().await()
    try {
      val offline = withTimeout(5_000) { repository.getProfile(aliceUid) }
      assertEquals(online, offline)
      assertNotNull(offline!!.createdAt)
    } finally {
      db.enableNetwork().await()
    }
  }

  @Test
  fun offlineUpdateWithoutExistingProfileCreatesNothing(): Unit = runBlocking {
    db.disableNetwork().await()
    try {
      val elapsed = timed { withTimeout(5_000) { repository.updateProfile(alice()) } }
      assertTrue("updateProfile took $elapsed ms", elapsed < 1_000)
      assertNull("nothing written to the local cache", cachedSnapshot(aliceUid))
    } finally {
      db.enableNetwork().await()
    }

    awaitServerAck()
    delay(500)
    assertFalse(serverSnapshot(aliceUid).exists())
    assertNull(cachedSnapshot(aliceUid))
  }

  @Test
  fun offlineObserveEmitsLocalWrite(): Unit = runBlocking {
    db.disableNetwork().await()
    try {
      val observed =
          async(Dispatchers.IO) {
            withTimeout(10_000) { repository.observeProfile(aliceUid).first { it != null } }
          }
      repository.createProfile(alice())
      assertSameContent(alice(), observed.await())
    } finally {
      db.enableNetwork().await()
    }
  }
}
