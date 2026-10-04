package com.github.se.oncompanion.model.user

import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Checks that [FakeUserProfileRepository] honours the [UserProfileRepository] contract. */
class FakeUserProfileRepositoryTest {

  private val now = Instant.parse("2026-01-02T03:04:05Z")
  private val repository = FakeUserProfileRepository(clock = { now })

  private val alice =
      UserProfile(
          uid = "alice",
          role = Role.PATIENT,
          firstName = "Alice",
          familyName = "Martin",
          cancerType = "Breast cancer",
      )

  private suspend fun assertThrowsIllegalArgument(block: suspend () -> Unit) {
    try {
      block()
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }

  @Test
  fun getUnknownProfileReturnsNull() = runTest {
    assertNull(repository.getProfile("nobody"))
    assertTrue(repository.profiles.isEmpty())
  }

  @Test
  fun createThenGetReturnsProfileWithClockCreatedAt() = runTest {
    repository.createProfile(alice)

    assertEquals(alice.copy(createdAt = now), repository.getProfile("alice"))
    assertEquals(mapOf("alice" to alice.copy(createdAt = now)), repository.profiles)
  }

  @Test
  fun createIgnoresGivenCreatedAt() = runTest {
    repository.createProfile(alice.copy(createdAt = Instant.EPOCH))

    assertEquals(now, repository.getProfile("alice")!!.createdAt)
  }

  @Test
  fun defaultClockUsesCurrentTime() = runTest {
    val defaultRepository = FakeUserProfileRepository()
    val before = Instant.now()
    defaultRepository.createProfile(alice)
    val after = Instant.now()

    val createdAt = defaultRepository.getProfile("alice")!!.createdAt!!
    assertTrue(!createdAt.isBefore(before) && !createdAt.isAfter(after))
  }

  @Test
  fun createInvalidProfileThrowsAndStoresNothing() = runTest {
    val invalids =
        listOf(
            alice.copy(uid = " "),
            alice.copy(firstName = ""),
            alice.copy(firstName = "a".repeat(51)),
            alice.copy(familyName = "b".repeat(51)),
            alice.copy(cancerType = "c".repeat(101)),
        )
    for (invalid in invalids) assertThrowsIllegalArgument { repository.createProfile(invalid) }

    assertTrue(repository.profiles.isEmpty())
  }

  @Test
  fun updateChangesOnlyEditableFields() = runTest {
    repository.createProfile(alice)

    repository.updateProfile(
        alice.copy(
            role = Role.CAREGIVER,
            firstName = "Alicia",
            familyName = null,
            cancerType = "Lymphoma",
            createdAt = Instant.EPOCH,
        )
    )

    assertEquals(
        alice.copy(
            firstName = "Alicia",
            familyName = null,
            cancerType = "Lymphoma",
            createdAt = now,
        ),
        repository.getProfile("alice"),
    )
  }

  @Test
  fun updateUnknownProfileCreatesNothing() = runTest {
    repository.updateProfile(alice)

    assertNull(repository.getProfile("alice"))
    assertTrue(repository.profiles.isEmpty())
  }

  @Test
  fun updateInvalidProfileThrowsAndKeepsStoredProfile() = runTest {
    repository.createProfile(alice)

    assertThrowsIllegalArgument { repository.updateProfile(alice.copy(firstName = " ")) }

    assertEquals(alice.copy(createdAt = now), repository.getProfile("alice"))
  }

  @Test
  fun updateInvalidUnknownProfileStillThrows() = runTest {
    assertThrowsIllegalArgument { repository.updateProfile(alice.copy(firstName = "")) }
  }

  @Test
  fun profilesAreIsolatedByUid() = runTest {
    val bob = UserProfile(uid = "bob", role = Role.CAREGIVER, firstName = "Bob")
    repository.createProfile(alice)
    repository.createProfile(bob)

    repository.updateProfile(bob.copy(firstName = "Robert"))

    assertEquals("Alice", repository.getProfile("alice")!!.firstName)
    assertEquals("Robert", repository.getProfile("bob")!!.firstName)
    assertEquals(setOf("alice", "bob"), repository.profiles.keys)
  }

  @Test
  fun profilesSnapshotDoesNotChangeAfterLaterWrites() = runTest {
    repository.createProfile(alice)
    val snapshot = repository.profiles

    repository.updateProfile(alice.copy(firstName = "Alicia"))

    assertEquals("Alice", snapshot["alice"]!!.firstName)
    assertEquals("Alicia", repository.profiles["alice"]!!.firstName)
  }

  @Test
  fun seedStoresProfileAsIs() = runTest {
    val seeded = alice.copy(createdAt = Instant.EPOCH)
    repository.seed(seeded)

    assertEquals(seeded, repository.getProfile("alice"))
  }

  @Test
  fun observeEmitsCurrentValueFirst() = runTest {
    assertNull(repository.observeProfile("alice").first())

    repository.createProfile(alice)
    assertEquals(alice.copy(createdAt = now), repository.observeProfile("alice").first())
  }

  @Test
  fun observeEmitsNullThenCreatedThenUpdated() =
      runTest(UnconfinedTestDispatcher()) {
        val emissions = mutableListOf<UserProfile?>()
        val job = launch { repository.observeProfile("alice").collect { emissions.add(it) } }

        repository.createProfile(alice)
        repository.updateProfile(alice.copy(firstName = "Alicia"))
        job.cancel()

        assertEquals(
            listOf(
                null,
                alice.copy(createdAt = now),
                alice.copy(firstName = "Alicia", createdAt = now),
            ),
            emissions,
        )
      }

  @Test
  fun observeIgnoresChangesToOtherProfiles() =
      runTest(UnconfinedTestDispatcher()) {
        val emissions = mutableListOf<UserProfile?>()
        val job = launch { repository.observeProfile("alice").collect { emissions.add(it) } }

        repository.createProfile(UserProfile(uid = "bob", role = Role.CAREGIVER, firstName = "Bob"))
        repository.updateProfile(alice) // no profile: no-op, no emission
        job.cancel()

        assertEquals(listOf<UserProfile?>(null), emissions)
      }

  @Test
  fun observeCanBeCollectedTwice() = runTest {
    repository.createProfile(alice)
    val flow = repository.observeProfile("alice")

    assertEquals(listOf(alice.copy(createdAt = now)), flow.take(1).toList())
    assertEquals(listOf(alice.copy(createdAt = now)), flow.take(1).toList())
  }

  @Test
  fun getProfileErrorIsThrownByGetAndObserve() = runTest {
    repository.createProfile(alice)
    val error = IllegalStateException("boom")
    repository.getProfileError = error

    try {
      repository.getProfile("alice")
      fail("Expected getProfileError")
    } catch (e: IllegalStateException) {
      assertSame(error, e)
    }
    try {
      repository.observeProfile("alice").first()
      fail("Expected getProfileError")
    } catch (e: IllegalStateException) {
      assertSame(error, e)
    }

    repository.getProfileError = null
    assertEquals(alice.copy(createdAt = now), repository.getProfile("alice"))
  }

  @Test
  fun writeErrorIsThrownByCreateAndUpdateAndStoresNothing() = runTest {
    repository.createProfile(alice)
    val error = RuntimeException("network")
    repository.writeError = error

    try {
      repository.createProfile(alice.copy(uid = "bob"))
      fail("Expected writeError")
    } catch (e: RuntimeException) {
      assertSame(error, e)
    }
    try {
      repository.updateProfile(alice.copy(firstName = "Alicia"))
      fail("Expected writeError")
    } catch (e: RuntimeException) {
      assertSame(error, e)
    }

    assertEquals(mapOf("alice" to alice.copy(createdAt = now)), repository.profiles)

    repository.writeError = null
    repository.updateProfile(alice.copy(firstName = "Alicia"))
    assertEquals("Alicia", repository.getProfile("alice")!!.firstName)
  }
}
