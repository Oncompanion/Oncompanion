package com.github.se.oncompanion.model.appointment

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the shared fake follows the appointment storage contract used by feature tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeAppointmentRepositoryTest {
  private val now = Instant.parse("2026-10-10T12:00:00Z")
  private val repository = FakeAppointmentRepository(Clock.fixed(now, ZoneOffset.UTC))

  private fun appointment(time: String = "2026-10-15T08:00:00Z", id: String = "") =
      Appointment(
          id = id,
          title = "Consultation",
          scheduledAt = Instant.parse(time),
          type = AppointmentType.CONSULTATION,
      )

  /** Distinguishes an empty collection from an existing appointment. */
  @Test
  fun emptyStoreEmitsAnEmptyListAndMissingObservationEmitsNull() = runTest {
    assertEquals(emptyList<Appointment>(), repository.observeAppointments("alice").first())
    assertNull(repository.observeAppointment("alice", "missing").first())
  }

  /** Assigns fresh identity and creation time without losing patient fields. */
  @Test
  fun additionsGenerateIdsAndReplaceIncomingCreationMetadata() = runTest {
    val input =
        appointment()
            .copy(
                id = "ignored",
                createdAt = Instant.EPOCH,
                location = "HUG",
                notes = "Bring documents",
            )
    val first = repository.addAppointment("alice", input)
    val second = repository.addAppointment("alice", appointment())

    assertEquals("appointment-1", first)
    assertEquals("appointment-2", second)
    assertEquals(
        input.copy(id = first, createdAt = now),
        repository.observeAppointment("alice", first).first(),
    )
    assertNull(repository.observeAppointment("alice", "ignored").first())
  }

  /** Protects existing test fixtures when adding new appointments. */
  @Test
  fun seededIdsAreNotOverwrittenByGeneratedIds() = runTest {
    val seeded = appointment(id = "appointment-1")
    repository.seed("alice", seeded)
    assertEquals("appointment-2", repository.addAppointment("alice", appointment()))
    assertEquals(seeded, repository.observeAppointment("alice", seeded.id).first())
    assertEquals(2, repository.appointments("alice").size)
  }

  /** Keeps appointment ordering deterministic when times are equal. */
  @Test
  fun observationOrdersByTimeThenId() = runTest {
    val later = appointment("2026-10-16T08:00:00Z", "later")
    val first = appointment(id = "a")
    val second = appointment(id = "b")
    repository.seed("alice", later, second, first)

    assertEquals(listOf(first, second, later), repository.observeAppointments("alice").first())
  }

  /** Prevents one patient from receiving another patients appointments. */
  @Test
  fun observationsKeepUsersSeparated() = runTest {
    val alice = appointment(id = "alice-entry")
    val bob = appointment(id = "bob-entry")
    repository.seed("alice", alice)
    repository.seed("bob", bob)

    assertEquals(listOf(alice), repository.observeAppointments("alice").first())
    assertEquals(listOf(bob), repository.observeAppointments("bob").first())
    assertNull(repository.observeAppointment("bob", alice.id).first())
    assertNull(repository.observeAppointment("alice", bob.id).first())
    assertEquals(emptyList<Appointment>(), repository.appointments("nobody"))
  }

  /** Makes newly saved data available to an already observing screen. */
  @Test
  fun additionUpdatesAnActiveObserver() = runTest {
    val emissions = mutableListOf<List<Appointment>>()
    val job =
        launch(StandardTestDispatcher(testScheduler)) {
          repository.observeAppointments("alice").take(2).toList(emissions)
        }
    runCurrent()
    val id = repository.addAppointment("alice", appointment())
    runCurrent()
    job.join()

    assertEquals(emptyList<Appointment>(), emissions[0])
    assertEquals(listOf(appointment().copy(id = id, createdAt = now)), emissions[1])
  }

  /** Avoids unrelated emissions and releases a cancelled observation. */
  @Test
  fun otherUsersWritesDoNotEmitAndCancellationStopsObservation() = runTest {
    val emissions = mutableListOf<List<Appointment>>()
    val job =
        launch(StandardTestDispatcher(testScheduler)) {
          repository.observeAppointments("alice").toList(emissions)
        }
    runCurrent()
    repository.addAppointment("bob", appointment())
    runCurrent()
    assertEquals(listOf(emptyList<Appointment>()), emissions)

    job.cancel()
    runCurrent()
    repository.addAppointment("alice", appointment())
    runCurrent()
    assertEquals(listOf(emptyList<Appointment>()), emissions)
  }

  /** Keeps the selected record current without emitting unrelated writes or duplicate values. */
  @Test
  fun singleObservationReceivesChangesAndStopsAfterCancellation() = runTest {
    val emissions = mutableListOf<Appointment?>()
    val job =
        launch(StandardTestDispatcher(testScheduler)) {
          repository.observeAppointment("alice", "fixture").toList(emissions)
        }
    runCurrent()
    assertEquals(listOf<Appointment?>(null), emissions)

    val original = appointment(id = "fixture")
    repository.seed("alice", original)
    runCurrent()
    assertEquals(listOf(null, original), emissions)

    repository.seed("alice", original)
    repository.seed("alice", appointment(id = "other"))
    repository.seed("bob", original.copy(title = "Another patient"))
    runCurrent()
    assertEquals(listOf(null, original), emissions)

    val updated = original.copy(title = "Rescheduled consultation")
    repository.seed("alice", updated)
    runCurrent()
    assertEquals(listOf(null, original, updated), emissions)

    job.cancel()
    runCurrent()
    repository.seed("alice", original)
    runCurrent()
    assertEquals(listOf(null, original, updated), emissions)
  }

  /** Allows tests to prepare changed data under the same identity. */
  @Test
  fun seedCanReplaceAnExistingFixture() = runTest {
    val original = appointment(id = "fixture")
    repository.seed("alice", original)
    val updated = original.copy(title = "Updated consultation")
    repository.seed("alice", updated)
    assertEquals(listOf(updated), repository.appointments("alice"))
  }

  /** Catches missing fixture identities instead of silently collapsing appointments. */
  @Test
  fun seedRejectsBlankIdsWithoutStoringAppointments() {
    for (id in listOf("", " ", "\t\n")) {
      val failure = runCatching { repository.seed("alice", appointment(id = id)) }.exceptionOrNull()
      assertTrue(failure is IllegalArgumentException)
      assertTrue(repository.appointments("alice").isEmpty())
    }
  }

  /** Rejects the complete batch without replacing existing fixtures or inserting new ones. */
  @Test
  fun invalidSeedBatchLeavesExistingStorageUnchanged() {
    val original = appointment(id = "fixture")
    repository.seed("alice", original)

    val failure = runCatching {
      repository.seed(
          "alice",
          original.copy(title = "Changed consultation"),
          appointment(id = "new-fixture"),
          appointment(),
      )
    }
        .exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertEquals(listOf(original), repository.appointments("alice"))
  }

  /** Rejects invalid input without leaving partial test data. */
  @Test
  fun invalidAdditionStoresNothing() = runTest {
    val failure = runCatching {
      repository.addAppointment("alice", appointment().copy(title = " "))
    }
        .exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(repository.appointments("alice").isEmpty())
  }

  /** Rejects user IDs that would create invalid document paths. */
  @Test
  fun invalidUserPathsAreRejectedByEveryOperation() = runTest {
    for (uid in listOf("", " ", "alice/appointments")) {
      assertTrue(
          runCatching { repository.observeAppointments(uid) }.exceptionOrNull()
              is IllegalArgumentException
      )
      assertTrue(
          runCatching { repository.observeAppointment(uid, "entry") }.exceptionOrNull()
              is IllegalArgumentException
      )
      assertTrue(
          runCatching { repository.addAppointment(uid, appointment()) }.exceptionOrNull()
              is IllegalArgumentException
      )
    }
  }

  /** Rejects invalid single-appointment paths. */
  @Test
  fun invalidDocumentIdsAreRejected() = runTest {
    for (id in listOf("", " ", "entry/nested")) {
      assertTrue(
          runCatching { repository.observeAppointment("alice", id) }.exceptionOrNull()
              is IllegalArgumentException
      )
    }
  }

  /** Exposes observation errors to the collecting screen. */
  @Test
  fun observationFailurePropagatesWhenCollectionStarts() = runTest {
    val source = repository.observeAppointments("alice")
    val failure = IllegalStateException("Read failed")
    repository.observeError = failure
    assertSame(failure, runCatching { source.first() }.exceptionOrNull())
  }

  /** Keeps a failed read distinguishable from an absent appointment. */
  @Test
  fun singleObservationFailureIsNotReportedAsMissing() = runTest {
    val failure = IllegalStateException("Read failed")
    val source = repository.observeAppointment("alice", "missing")
    repository.observeError = failure
    assertSame(failure, runCatching { source.first() }.exceptionOrNull())
    repository.observeError = null
    assertNull(source.first())
  }

  /** Preserves storage on failure and allows the caller to retry. */
  @Test
  fun writeFailureStoresNothingAndCanBeClearedForRetry() = runTest {
    val failure = IllegalStateException("Write failed")
    repository.writeError = failure
    assertSame(
        failure,
        runCatching { repository.addAppointment("alice", appointment()) }.exceptionOrNull(),
    )
    assertTrue(repository.appointments("alice").isEmpty())

    repository.writeError = null
    val id = repository.addAppointment("alice", appointment())
    assertEquals(
        appointment().copy(id = id, createdAt = now),
        repository.observeAppointment("alice", id).first(),
    )
  }
}
