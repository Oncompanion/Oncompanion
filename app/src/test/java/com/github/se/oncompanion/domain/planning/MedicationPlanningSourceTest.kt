// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.planning

import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.planning.*
import com.github.se.oncompanion.model.planning.observeItems
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Verifies saved prescriptions become bounded calendar occurrences without inferred doses. */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationPlanningSourceTest {
  private val day = LocalDate.of(2026, 10, 25)
  private val range = PlanningRange(day, day.plusDays(7), ZoneId.of("Europe/Zurich"))
  private val medications = FakeMedicationRepository()
  private val source = MedicationPlanningSource(medications)

  private fun medication(start: LocalDate = day, duration: Int? = 3) =
      Medication(
          "m",
          "p",
          "Recorded medication",
          "1 tablet",
          "Mondays and Thursdays",
          start,
          duration,
      )

  private fun prescription(vararg entries: Medication) =
      Prescription(
          entries.first().prescriptionId,
          prescribedOn = day,
          medications = entries.toList(),
      )

  /** Includes exactly the active days intersecting the requested week. */
  @Test
  fun clipsFiniteAndOpenEndedDurationsToTheRequestedRange() = runTest {
    val cases =
        listOf(
            Triple(-2L, 2, emptyList<Long>()),
            Triple(-2L, 3, listOf(0L)),
            Triple(0L, 1, listOf(0L)),
            Triple(0L, 3, listOf(0L, 1L, 2L)),
            Triple(5L, 4, listOf(5L, 6L)),
            Triple(7L, 1, emptyList<Long>()),
            Triple(-100L, null, (0L..6L).toList()),
            Triple(5L, null, listOf(5L, 6L)),
            Triple(8L, null, emptyList<Long>()),
        )
    for ((offset, duration, expected) in cases) {
      medications.seed("patient", prescription(medication(day.plusDays(offset), duration)))
      var latest: List<PlanningItem>? = null
      val job =
          backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            source.observeItems("patient", range).collect { latest = it }
          }
      runCurrent()
      assertEquals(
          "start=$offset duration=$duration",
          expected.map(day::plusDays),
          latest!!.map { (it.timing as PlanningTiming.DateOnly).date },
      )
      job.cancel()
    }
  }

  /** Calendar dates survive DST, year changes and different display zones. */
  @Test
  fun calendarDatesDoNotShiftWithDisplayZoneOrOverflowAtTheDateLimit() = runTest {
    for (start in
        listOf(
            LocalDate.of(2026, 3, 28),
            day,
            LocalDate.of(2026, 12, 31),
            LocalDate.MAX.minusDays(2),
        )) {
      medications.seed("patient", prescription(medication(start, 5)))
      val requested = PlanningRange(start, start.plusDays(2), ZoneId.of("Pacific/Honolulu"))
      var latest = emptyList<PlanningItem>()
      val job =
          backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            source.observeItems("patient", requested).collect { latest = it }
          }
      runCurrent()
      assertEquals(
          listOf(start, start.plusDays(1)),
          latest.map { it.timing.dateIn(requested.zoneId) },
      )
      job.cancel()
    }
  }

  /** The Overview's one-year range bounds even medications that began years earlier. */
  @Test
  fun openEndedMedicationExpandsOnlyInsideTheYearRequestedByOverview() = runTest {
    medications.seed("patient", prescription(medication(day.minusYears(10), null)))
    var latest = emptyList<PlanningItem>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems("patient", range.copy(endDateExclusive = day.plusYears(1))).collect {
        latest = it
      }
    }
    runCurrent()
    assertEquals(365, latest.size)
    assertEquals(day, (latest.first().timing as PlanningTiming.DateOnly).date)
    assertEquals(
        day.plusYears(1).minusDays(1),
        (latest.last().timing as PlanningTiming.DateOnly).date,
    )
  }

  /** Edits replace occurrences, preserve stable IDs and never substitute dosage for frequency. */
  @Test
  fun savedEditsAndRemovalReplaceOccurrencesWithoutDuplicates() = runTest {
    val original = medication()
    medications.addPrescription("patient", prescription(original))
    var latest = emptyList<PlanningItem>()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems("patient", range).collect { latest = it }
    }
    runCurrent()
    val keys = latest.map { it.key }
    assertEquals(3, keys.distinct().size)
    assertEquals(PlanningSource.Medication("p", "m"), latest.first().source)
    assertEquals(original.name, latest.first().title)
    assertEquals(original.frequency, latest.first().frequency)
    assertNull(latest.first().subtitle)
    val renamed = original.copy(name = "Renamed", frequency = null)
    medications.updatePrescription("patient", prescription(renamed))
    runCurrent()
    assertEquals(keys, latest.map { it.key })
    assertTrue(latest.all { it.title == "Renamed" && it.frequency == null })
    medications.updatePrescription(
        "patient",
        prescription(renamed.copy(startDate = day.plusDays(1), durationDays = 1)),
    )
    runCurrent()
    assertEquals(listOf(day.plusDays(1)), latest.map { it.timing.dateIn(range.zoneId) })
    // Equal medication IDs in different prescriptions are distinct occurrences.
    medications.addPrescription(
        "patient",
        prescription(original.copy(prescriptionId = "other", durationDays = 1)),
    )
    runCurrent()
    assertEquals(2, latest.map { it.key }.distinct().size)
    medications.deletePrescription("patient", "p")
    runCurrent()
    assertEquals("other", (latest.single().source as PlanningSource.Medication).prescriptionId)
    medications.deletePrescription("patient", "other")
    runCurrent()
    assertTrue(latest.isEmpty())
  }

  /** Removing one medication leaves its siblings, including after collecting again. */
  @Test
  fun removingOneMedicationAndReopeningDoesNotKeepOldOccurrences() = runTest {
    val a = medication(duration = 1)
    val b = a.copy(id = "b")
    medications.seed("patient", prescription(a, b))
    var latest = emptyList<PlanningItem>()
    val job =
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
          source.observeItems("patient", range).collect { latest = it }
        }
    runCurrent()
    assertEquals(2, latest.size)
    medications.updatePrescription("patient", prescription(b))
    runCurrent()
    assertEquals(PlanningSource.Medication("p", "b"), latest.single().source)
    job.cancel()
    backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
      source.observeItems("patient", range).collect { latest = it }
    }
    runCurrent()
    assertEquals(1, latest.size)
  }

  /** A read failure reaches the caller instead of showing an empty calendar. */
  @Test
  fun readFailureReachesTheCaller() = runTest {
    medications.readError = IllegalStateException("Read failed")

    val failure = runCatching { source.observeItems("patient", range).first() }.exceptionOrNull()

    assertEquals("Read failed", failure?.message)
  }
}
