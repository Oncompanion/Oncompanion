package com.github.se.oncompanion.model.symptom

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

/** Checks that [FakeSymptomRepository] honours the [SymptomRepository] contract. */
class FakeSymptomRepositoryTest {

  private val now = Instant.parse("2026-10-05T12:00:00Z")
  private val repository = FakeSymptomRepository(clock = { now })

  private fun entry(occurredAt: String, type: SymptomType = SymptomType.FATIGUE) =
      SymptomEntry(type = type, intensity = 4, occurredAt = Instant.parse(occurredAt))

  @Test
  fun addReturnsNewIdsAndStoresWithClockCreatedAt() = runTest {
    val first = repository.addSymptom("alice", entry("2026-10-05T08:00:00Z"))
    val second = repository.addSymptom("alice", entry("2026-10-05T09:00:00Z").copy(id = "ignored"))

    assertEquals("symptom-1", first)
    assertEquals("symptom-2", second)
    assertEquals(
        setOf(
            entry("2026-10-05T08:00:00Z").copy(id = first, createdAt = now),
            entry("2026-10-05T09:00:00Z").copy(id = second, createdAt = now),
        ),
        repository.symptoms("alice").toSet(),
    )
  }

  @Test
  fun addInvalidEntryThrowsAndStoresNothing() = runTest {
    try {
      repository.addSymptom("alice", entry("2026-10-05T08:00:00Z").copy(intensity = 0))
      fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      // expected
    }
    assertTrue(repository.symptoms("alice").isEmpty())
  }

  @Test
  fun addWithWriteErrorThrowsItAndStoresNothing() = runTest {
    val error = IllegalStateException("boom")
    repository.writeError = error
    try {
      repository.addSymptom("alice", entry("2026-10-05T08:00:00Z"))
      fail("Expected the write error")
    } catch (e: IllegalStateException) {
      assertSame(error, e)
    }
    assertTrue(repository.symptoms("alice").isEmpty())
  }

  @Test
  fun observeSymptomsSortsNewestFirstAndOnlyForThatUser() = runTest {
    val old = entry("2026-10-01T08:00:00Z").copy(id = "old")
    val recent = entry("2026-10-05T08:00:00Z").copy(id = "recent")
    repository.seed("alice", old, recent)
    repository.seed("bob", entry("2026-10-06T08:00:00Z").copy(id = "bob"))

    assertEquals(listOf(recent, old), repository.observeSymptoms("alice").first())
    assertEquals(emptyList<SymptomEntry>(), repository.observeSymptoms("nobody").first())
  }

  @Test
  fun observeSymptomsEmitsEachChange() = runTest {
    val emissions = mutableListOf<List<SymptomEntry>>()
    val job =
        launch(UnconfinedTestDispatcher(testScheduler)) {
          repository.observeSymptoms("alice").take(2).toList(emissions)
        }

    val id = repository.addSymptom("alice", entry("2026-10-05T08:00:00Z"))
    job.join()

    assertEquals(emptyList<SymptomEntry>(), emissions[0])
    assertEquals(listOf(id), emissions[1].map { it.id })
  }

  @Test
  fun observeSymptomEmitsEntryOrNull() = runTest {
    val stored = entry("2026-10-05T08:00:00Z").copy(id = "s1")
    repository.seed("alice", stored)

    assertEquals(stored, repository.observeSymptom("alice", "s1").first())
    assertNull(repository.observeSymptom("alice", "missing").first())
    assertNull(repository.observeSymptom("bob", "s1").first())
  }

  @Test
  fun observeErrorFailsBothFlows() = runTest {
    val error = IllegalStateException("offline and no cache")
    repository.observeError = error

    for (flow in listOf(repository.observeSymptoms("alice"), repository.observeSymptom("a", "b"))) {
      try {
        flow.first()
        fail("Expected the observe error")
      } catch (e: IllegalStateException) {
        assertSame(error, e)
      }
    }
  }
}
