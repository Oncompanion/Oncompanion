package com.github.se.oncompanion.model.user

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.utils.EmulatorTestData
import com.github.se.oncompanion.utils.FirebaseEmulator
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

  @Before
  fun setUp(): Unit = runBlocking {
    EmulatorTestData.signOut()
    FirebaseEmulator.firestore.enableNetwork().await()
    aliceUid = EmulatorTestData.createUser("alice")
    repository = UserProfileRepositoryFirestore(FirebaseEmulator.firestore)
  }

  @After
  fun tearDown(): Unit = runBlocking {
    FirebaseEmulator.firestore.enableNetwork().await()
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

  private fun assertSameContent(expected: UserProfile, actual: UserProfile?) {
    assertNotNull(actual)
    assertEquals(expected.copy(createdAt = null), actual!!.copy(createdAt = null))
  }

  @Test
  fun defaultConstructorUsesSharedInstance(): Unit = runBlocking {
    val defaultRepository = UserProfileRepositoryFirestore()
    defaultRepository.createProfile(alice())
    assertSameContent(alice(), defaultRepository.getProfile(aliceUid))
  }

  @Test
  fun createThenGetRoundTrip(): Unit = runBlocking {
    repository.createProfile(alice())

    val stored = repository.getProfile(aliceUid)

    assertSameContent(alice(), stored)
    assertNotNull("createdAt is set once the server confirms", stored!!.createdAt)
  }

  @Test
  fun createWithOnlyRequiredFieldsRoundTrips(): Unit = runBlocking {
    val minimal = UserProfile(uid = aliceUid, role = Role.CAREGIVER, firstName = "Al")
    repository.createProfile(minimal)

    val stored = repository.getProfile(aliceUid)
    assertSameContent(minimal, stored)
    assertNull(stored!!.familyName)
    assertNull(stored.cancerType)
  }

  @Test
  fun createIgnoresClientCreatedAt(): Unit = runBlocking {
    repository.createProfile(alice().copy(createdAt = Instant.EPOCH))

    val stored = repository.getProfile(aliceUid)
    assertNotNull(stored!!.createdAt)
    assertNotEquals(Instant.EPOCH, stored.createdAt)
    assertTrue(stored.createdAt!!.isAfter(Instant.parse("2020-01-01T00:00:00Z")))
  }

  @Test
  fun createdAtIsWrittenAsTimestampField(): Unit = runBlocking {
    repository.createProfile(alice())

    val snapshot =
        FirebaseEmulator.firestore
            .collection(UserProfileRepositoryFirestore.COLLECTION)
            .document(aliceUid)
            .get()
            .await()
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

  @Test
  fun updateChangesFieldsAndKeepsCreatedAt(): Unit = runBlocking {
    repository.createProfile(alice())
    val createdAt = repository.getProfile(aliceUid)!!.createdAt
    assertNotNull(createdAt)

    val updated =
        alice()
            .copy(
                role = Role.CAREGIVER,
                firstName = "Alicia",
                familyName = "Dupont",
                cancerType = "Lymphoma",
                createdAt = Instant.EPOCH,
            )
    repository.updateProfile(updated)

    val stored = repository.getProfile(aliceUid)
    assertSameContent(updated, stored)
    assertEquals(createdAt, stored!!.createdAt)
  }

  @Test
  fun updateCanClearOptionalFields(): Unit = runBlocking {
    repository.createProfile(alice())

    repository.updateProfile(alice().copy(familyName = null, cancerType = null))

    val stored = repository.getProfile(aliceUid)
    assertNotNull(stored)
    assertNull(stored!!.familyName)
    assertNull(stored.cancerType)
    assertEquals("Alice", stored.firstName)
  }

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

        val updated = alice().copy(firstName = "Alicia", role = Role.CAREGIVER)
        repository.updateProfile(updated)
        while (current?.firstName != "Alicia") current = emissions.receive()
        assertSameContent(updated, current)
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
  }

  @Test
  fun createForAnotherUserIsRejectedByServer(): Unit = runBlocking {
    val bobUid = EmulatorTestData.createUser("bob")
    EmulatorTestData.signIn("alice")

    try {
      repository.createProfile(alice().copy(uid = bobUid))
      fail("Expected PERMISSION_DENIED")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

  @Test
  fun updateOfAnotherUsersProfileIsRejectedByServer(): Unit = runBlocking {
    repository.createProfile(alice())
    EmulatorTestData.createUser("bob")

    try {
      repository.updateProfile(alice().copy(firstName = "Hacked"))
      fail("Expected PERMISSION_DENIED")
    } catch (e: FirebaseFirestoreException) {
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
    }
  }

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

  @Test
  fun offlineCreateReturnsAfterTimeoutAndIsReadableFromCache(): Unit = runBlocking {
    val offlineRepository = UserProfileRepositoryFirestore(FirebaseEmulator.firestore, 500L)
    FirebaseEmulator.firestore.disableNetwork().await()
    try {
      val start = System.currentTimeMillis()
      withTimeout(5_000) { offlineRepository.createProfile(alice()) }
      val elapsed = System.currentTimeMillis() - start
      assertTrue("createProfile took $elapsed ms", elapsed < 3_000)

      assertSameContent(alice(), offlineRepository.getProfile(aliceUid))

      withTimeout(5_000) { offlineRepository.updateProfile(alice().copy(firstName = "Ali")) }
      assertEquals("Ali", offlineRepository.getProfile(aliceUid)?.firstName)
    } finally {
      FirebaseEmulator.firestore.enableNetwork().await()
    }

    // Once back online the pending writes sync and the server sets createdAt.
    withTimeout(10_000) { FirebaseEmulator.firestore.waitForPendingWrites().await() }
    val synced = repository.getProfile(aliceUid)
    assertEquals("Ali", synced?.firstName)
    assertNotNull(synced!!.createdAt)
  }

  @Test
  fun offlineObserveEmitsLocalWrite(): Unit = runBlocking {
    val offlineRepository = UserProfileRepositoryFirestore(FirebaseEmulator.firestore, 500L)
    FirebaseEmulator.firestore.disableNetwork().await()
    try {
      val observed =
          async(Dispatchers.IO) {
            withTimeout(10_000) { offlineRepository.observeProfile(aliceUid).first { it != null } }
          }
      offlineRepository.createProfile(alice())
      assertSameContent(alice(), observed.await())
    } finally {
      FirebaseEmulator.firestore.enableNetwork().await()
    }
  }
}
