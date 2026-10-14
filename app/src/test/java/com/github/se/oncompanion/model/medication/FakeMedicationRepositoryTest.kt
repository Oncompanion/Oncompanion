// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.model.medication

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Checks that [FakeMedicationRepository] honours the [MedicationRepository] contract. */
class FakeMedicationRepositoryTest {

  private val now = Instant.parse("2026-09-29T10:00:00Z")
  private val repository = FakeMedicationRepository(clock = { now })

  private val september29 = LocalDate.of(2026, 9, 29)

  private fun medication(
      id: String,
      prescriptionId: String,
      name: String = "Ondansetron 8 mg",
      startDate: LocalDate = september29,
  ) = Medication(id = id, prescriptionId = prescriptionId, name = name, startDate = startDate)

  private val recent =
      Prescription(
          id = "recent",
          prescribedBy = "Dr. Martin",
          prescribedOn = september29,
          medications =
              listOf(
                  medication("ondansetron", "recent"),
                  medication("dexamethasone", "recent", name = "Dexamethasone 4 mg"),
              ),
      )

  private val older =
      Prescription(
          id = "older",
          prescribedOn = LocalDate.of(2026, 9, 12),
          medications =
              listOf(
                  medication(
                      "paracetamol",
                      "older",
                      name = "Paracetamol 1 g",
                      startDate = LocalDate.of(2026, 9, 12),
                  )
              ),
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
  fun newIdsAreUnique() {
    assertNotEquals(repository.newId(), repository.newId())
  }

  @Test
  fun observeUnknownPrescriptionEmitsNull() = runTest {
    assertNull(repository.observePrescription("alice", "nothing").first())
    assertTrue(repository.prescriptionsOf("alice").isEmpty())
  }

  @Test
  fun addThenObserveEmitsPrescriptionWithClockCreatedAt() = runTest {
    repository.addPrescription("alice", recent.copy(createdAt = Instant.EPOCH))

    assertEquals(
        recent.copy(createdAt = now),
        repository.observePrescription("alice", "recent").first(),
    )
  }

  @Test
  fun defaultClockUsesCurrentTime() = runTest {
    val defaultRepository = FakeMedicationRepository()
    val before = Instant.now()
    defaultRepository.addPrescription("alice", recent)
    val after = Instant.now()

    val createdAt = defaultRepository.observePrescription("alice", "recent").first()!!.createdAt!!
    assertTrue(!createdAt.isBefore(before) && !createdAt.isAfter(after))
  }

  @Test
  fun addInvalidPrescriptionThrowsAndStoresNothing() = runTest {
    assertThrowsIllegalArgument {
      repository.addPrescription("alice", recent.copy(medications = emptyList()))
    }

    assertTrue(repository.prescriptionsOf("alice").isEmpty())
  }

  @Test
  fun prescriptionsAreKeptPerUser() = runTest {
    repository.addPrescription("alice", recent)

    assertNull(repository.observePrescription("bob", "recent").first())
    assertTrue(repository.observePrescriptions("bob").first().isEmpty())
    assertTrue(repository.observeMedications("bob").first().isEmpty())
  }

  @Test
  fun prescriptionsAreOrderedMostRecentlyPrescribedFirst() = runTest {
    repository.addPrescription("alice", older)
    repository.addPrescription("alice", recent)

    assertEquals(
        listOf("recent", "older"),
        repository.observePrescriptions("alice").first().map { it.id },
    )
    assertEquals(listOf("recent", "older"), repository.prescriptionsOf("alice").map { it.id })
  }

  @Test
  fun samePrescriptionDateIsOrderedMostRecentlySavedFirst() = runTest {
    repository.seed("alice", older.copy(id = "first", createdAt = now.minusSeconds(60)))
    repository.seed("alice", older.copy(id = "second", createdAt = now))
    // Not confirmed by the server yet: counts as the most recent
    repository.seed("alice", older.copy(id = "pending", createdAt = null))

    assertEquals(
        listOf("pending", "second", "first"),
        repository.prescriptionsOf("alice").map { it.id },
    )
  }

  @Test
  fun medicationsKeepTheOrderTheyWereSavedIn() = runTest {
    repository.addPrescription("alice", recent)

    assertEquals(
        listOf("ondansetron", "dexamethasone"),
        repository.observePrescription("alice", "recent").first()!!.medications.map { it.id },
    )
  }

  @Test
  fun observeMedicationsEmitsAllMedicationsEarliestStartFirstThenByName() = runTest {
    repository.addPrescription("alice", recent)
    repository.addPrescription("alice", older)

    assertEquals(
        listOf("paracetamol", "dexamethasone", "ondansetron"),
        repository.observeMedications("alice").first().map { it.id },
    )
  }

  @Test
  fun observePrescriptionsEmitsCurrentValueThenChanges() =
      runTest(UnconfinedTestDispatcher()) {
        val emissions = mutableListOf<List<String>>()
        val job = launch {
          repository.observePrescriptions("alice").take(3).toList().mapTo(emissions) { list ->
            list.map { it.id }
          }
        }

        repository.addPrescription("alice", older)
        repository.addPrescription("alice", recent)
        job.join()

        assertEquals(listOf(emptyList(), listOf("older"), listOf("recent", "older")), emissions)
      }

  @Test
  fun observePrescriptionEmitsCurrentValueThenChanges() =
      runTest(UnconfinedTestDispatcher()) {
        val emissions = mutableListOf<String?>()
        val job = launch {
          repository.observePrescription("alice", "recent").take(4).toList().mapTo(emissions) {
            it?.prescribedBy
          }
        }

        repository.addPrescription("alice", recent)
        repository.updatePrescription("alice", recent.copy(prescribedBy = "Dr. Leroy"))
        // A change to another prescription is not emitted
        repository.addPrescription("alice", older)
        repository.deletePrescription("alice", "recent")
        job.join()

        assertEquals(listOf(null, "Dr. Martin", "Dr. Leroy", null), emissions)
      }

  @Test
  fun updateReplacesContentButKeepsCreatedAt() = runTest {
    repository.seed("alice", recent.copy(createdAt = Instant.EPOCH))
    val edited =
        recent.copy(
            prescribedBy = "Dr. Leroy",
            prescribedOn = LocalDate.of(2026, 9, 30),
            medications =
                listOf(medication("dexamethasone", "recent", name = "Dexamethasone 8 mg")),
            createdAt = now,
        )

    repository.updatePrescription("alice", edited)

    assertEquals(
        edited.copy(createdAt = Instant.EPOCH),
        repository.observePrescription("alice", "recent").first(),
    )
    // The medication left out of the update is gone
    assertEquals(
        listOf("dexamethasone"),
        repository.observeMedications("alice").first().map { it.id },
    )
  }

  @Test
  fun updateUnknownPrescriptionDoesNothing() = runTest {
    repository.updatePrescription("alice", recent)

    assertTrue(repository.prescriptionsOf("alice").isEmpty())
  }

  @Test
  fun updateInvalidPrescriptionThrowsAndKeepsStoredOne() = runTest {
    repository.addPrescription("alice", recent)

    assertThrowsIllegalArgument {
      repository.updatePrescription("alice", recent.copy(medications = emptyList()))
    }

    assertEquals(
        recent.copy(createdAt = now),
        repository.observePrescription("alice", "recent").first(),
    )
  }

  @Test
  fun deleteRemovesPrescriptionAndItsMedications() = runTest {
    repository.addPrescription("alice", recent)
    repository.addPrescription("alice", older)

    repository.deletePrescription("alice", "recent")

    assertNull(repository.observePrescription("alice", "recent").first())
    assertEquals(listOf("older"), repository.prescriptionsOf("alice").map { it.id })
    assertEquals(
        listOf("paracetamol"),
        repository.observeMedications("alice").first().map { it.id },
    )
  }

  @Test
  fun deleteUnknownPrescriptionDoesNothing() = runTest {
    repository.addPrescription("alice", recent)

    repository.deletePrescription("alice", "nothing")
    repository.deletePrescription("bob", "recent")

    assertEquals(listOf("recent"), repository.prescriptionsOf("alice").map { it.id })
  }

  @Test
  fun readErrorFailsEveryStream() = runTest {
    val error = IllegalStateException("boom")
    repository.readError = error

    val thrown =
        listOf<suspend () -> Unit>(
                { repository.observePrescription("alice", "recent").first() },
                { repository.observePrescriptions("alice").first() },
                { repository.observeMedications("alice").first() },
            )
            .map { read ->
              try {
                read()
                null
              } catch (e: IllegalStateException) {
                e
              }
            }

    thrown.forEach { assertSame(error, it) }
  }

  @Test
  fun failRunningReadsFailsTheStreamsBeingCollectedButNotLaterOnes() =
      runTest(UnconfinedTestDispatcher()) {
        repository.addPrescription("alice", recent)
        val error = IllegalStateException("permission lost")
        val failures =
            listOf(
                    repository.observePrescription("alice", "recent"),
                    repository.observePrescriptions("alice"),
                    repository.observeMedications("alice"),
                )
                .map { stream ->
                  async {
                    try {
                      stream.collect {}
                      null
                    } catch (e: IllegalStateException) {
                      e
                    }
                  }
                }

        repository.failRunningReads(error)

        // Coroutines may copy an exception between coroutines, so its message is compared
        failures.forEach { assertEquals(error.message, it.await()?.message) }
        assertEquals(
            recent.copy(createdAt = now),
            repository.observePrescription("alice", "recent").first(),
        )
      }

  @Test
  fun prescriptionObserversCountsTheRunningCollections() =
      runTest(UnconfinedTestDispatcher()) {
        assertEquals(0, repository.prescriptionObservers)

        val first = launch { repository.observePrescription("alice", "recent").collect {} }
        val second = launch { repository.observePrescription("bob", "older").collect {} }
        assertEquals(2, repository.prescriptionObservers)

        first.cancel()
        second.cancel()
        assertEquals(0, repository.prescriptionObservers)
      }

  @Test
  fun writeErrorIsThrownByEveryWriteAndStoresNothing() = runTest {
    repository.seed("alice", older)
    val error = IllegalStateException("boom")
    repository.writeError = error

    val thrown =
        listOf<suspend () -> Unit>(
                { repository.addPrescription("alice", recent) },
                { repository.updatePrescription("alice", older.copy(prescribedBy = "Dr. Leroy")) },
                { repository.deletePrescription("alice", "older") },
            )
            .map { write ->
              try {
                write()
                null
              } catch (e: IllegalStateException) {
                e
              }
            }

    thrown.forEach { assertSame(error, it) }
    assertEquals(listOf(older), repository.prescriptionsOf("alice"))
  }
}
