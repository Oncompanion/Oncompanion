// Co-authored-by: Codex <noreply@openai.com>

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

  /** Updates all editable fields without changing identity, metadata or another owner's data. */
  @Test
  fun updatePreservesIdentityAndMetadataAndClearsOptionalFields() = runTest {
    val original =
        appointment(id = "shared").copy(createdAt = now, location = "HUG", notes = "Note")
    repository.seed("alice", original)
    repository.seed("bob", original)
    val changed =
        original.copy(
            title = "Treatment",
            scheduledAt = now,
            type = AppointmentType.TREATMENT,
            location = null,
            notes = null,
            createdAt = Instant.EPOCH,
        )
    repository.updateAppointment("alice", changed)
    assertEquals(listOf(changed.copy(createdAt = now)), repository.appointments("alice"))
    assertEquals(listOf(original), repository.appointments("bob"))
  }

  /** Missing updates never insert a new record, including in another patient's collection. */
  @Test
  fun missingUpdateDoesNotCreateAnAppointment() = runTest {
    repository.updateAppointment("alice", appointment(id = "missing"))
    assertTrue(repository.appointments("alice").isEmpty())
  }

  /** Existing list and detail collectors receive edits and the resulting order changes. */
  @Test
  fun updateNotifiesObserversAndReordersAppointments() = runTest {
    val first = appointment(id = "first")
    val second = appointment("2026-10-16T08:00:00Z", "second")
    repository.seed("alice", first, second)
    val lists = mutableListOf<List<Appointment>>()
    val details = mutableListOf<Appointment?>()
    val listJob =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          repository.observeAppointments("alice").toList(lists)
        }
    val detailJob =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          repository.observeAppointment("alice", first.id).toList(details)
        }
    runCurrent()
    val changed = first.copy(scheduledAt = Instant.parse("2026-10-17T08:00:00Z"))
    repository.updateAppointment("alice", changed)
    runCurrent()
    assertEquals(listOf(listOf(first, second), listOf(second, changed)), lists)
    assertEquals(listOf(first, changed), details)
    listJob.cancel()
    detailJob.cancel()
  }

  /** Rejected fields and simulated enqueue failure retain the original and permit a retry. */
  @Test
  fun invalidAndFailedUpdatesLeaveStorageUnchanged() = runTest {
    val original = appointment(id = "saved")
    repository.seed("alice", original)
    for (invalid in
        listOf(
            original.copy(title = " "),
            original.copy(location = "x".repeat(201)),
            original.copy(notes = "x".repeat(1001)),
            original.copy(scheduledAt = Instant.MAX),
        )) {
      assertTrue(
          runCatching { repository.updateAppointment("alice", invalid) }.exceptionOrNull()
              is IllegalArgumentException
      )
    }
    for (value in listOf("", " ", "nested/path")) {
      assertTrue(
          runCatching { repository.updateAppointment(value, original) }.exceptionOrNull()
              is IllegalArgumentException
      )
      assertTrue(
          runCatching { repository.updateAppointment("alice", original.copy(id = value)) }
              .exceptionOrNull() is IllegalArgumentException
      )
    }
    val failure = IllegalStateException("Write failed")
    repository.writeError = failure
    val changed = original.copy(title = "Changed")
    assertSame(
        failure,
        runCatching { repository.updateAppointment("alice", changed) }.exceptionOrNull(),
    )
    assertEquals(listOf(original), repository.appointments("alice"))
    repository.writeError = null
    repository.updateAppointment("alice", changed)
    assertEquals(listOf(changed), repository.appointments("alice"))
  }

  /** Removal reaches existing list/detail observers while another patient's data is untouched. */
  @Test
  fun deletionNotifiesObserversAndKeepsUsersSeparated() = runTest {
    val original = appointment(id = "shared")
    repository.seed("alice", original)
    repository.seed("bob", original)
    val lists = mutableListOf<List<Appointment>>()
    val details = mutableListOf<Appointment?>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      repository.observeAppointments("alice").toList(lists)
    }
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      repository.observeAppointment("alice", original.id).toList(details)
    }
    runCurrent()
    repository.deleteAppointment("alice", original.id)
    runCurrent()
    repository.deleteAppointment("alice", original.id)
    repository.deleteAppointment("nobody", "missing")
    runCurrent()
    assertEquals(listOf(listOf(original), emptyList<Appointment>()), lists)
    assertEquals(listOf(original, null), details)
    assertEquals(listOf(original), repository.appointments("bob"))
    assertTrue(repository.appointments("nobody").isEmpty())
  }

  /** Invalid paths and simulated failures leave the appointment available for a retry. */
  @Test
  fun invalidAndFailedDeletionsLeaveStorageUnchanged() = runTest {
    val original = appointment(id = "saved")
    repository.seed("alice", original)
    for (value in listOf("", " ", "nested/path")) {
      assertTrue(
          runCatching { repository.deleteAppointment(value, original.id) }.exceptionOrNull()
              is IllegalArgumentException
      )
      assertTrue(
          runCatching { repository.deleteAppointment("alice", value) }.exceptionOrNull()
              is IllegalArgumentException
      )
    }
    val failure = IllegalStateException("Write failed")
    repository.writeError = failure
    assertSame(
        failure,
        runCatching { repository.deleteAppointment("alice", original.id) }.exceptionOrNull(),
    )
    assertEquals(listOf(original), repository.appointments("alice"))
    repository.writeError = null
    repository.deleteAppointment("alice", original.id)
    assertTrue(repository.appointments("alice").isEmpty())
  }

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
