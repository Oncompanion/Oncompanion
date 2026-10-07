package com.github.se.oncompanion.model.planning

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PlanningContractTest {
  private val date = LocalDate.of(2026, 10, 2)
  private val zone = ZoneId.of("Europe/Zurich")
  private val range = PlanningRange(date, date.plusDays(1), zone)
  private val start = range.startInclusive

  private fun reject(block: () -> Unit) {
    assertThrows(IllegalArgumentException::class.java) { block() }
  }

  @Test
  fun rangeIncludesStartButExcludesEndForDatesAndTimes() {
    assertTrue(start in range)
    assertTrue(start.plusSeconds(1) in range)
    assertFalse(start.minusSeconds(1) in range)
    assertFalse(range.endExclusive in range)
    assertTrue(date in range)
    assertFalse(date.minusDays(1) in range)
    assertFalse(date.plusDays(1) in range)
    assertTrue(PlanningTiming.Timed(start) in range)
    assertTrue(PlanningTiming.DateOnly(date) in range)
    assertFalse(PlanningTiming.DateOnly(date.plusDays(1)) in range)
    assertFalse(PlanningTiming.Timed(range.endExclusive) in range)
  }

  @Test
  fun emptyAndReversedRangesAreRejected() {
    reject { PlanningRange(date, date, zone) }
    reject { PlanningRange(date, date.minusDays(1), zone) }
  }

  @Test
  fun calendarRangeDerivesTimedBoundariesAcrossBothClockChanges() {
    val spring = PlanningRange(LocalDate.of(2026, 3, 23), LocalDate.of(2026, 3, 30), zone)
    val autumn = PlanningRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 26), zone)
    assertEquals(
        167,
        java.time.Duration.between(spring.startInclusive, spring.endExclusive).toHours(),
    )
    assertEquals(
        169,
        java.time.Duration.between(autumn.startInclusive, autumn.endExclusive).toHours(),
    )
    assertEquals(zone, autumn.zoneId)
    assertEquals(LocalDate.of(2026, 10, 19), autumn.startDateInclusive)
    assertEquals(LocalDate.of(2026, 10, 26), autumn.endDateExclusive)
  }

  @Test
  fun timedDatesUseDisplayZoneButCalendarDatesNeverShift() {
    val instant = Instant.parse("2026-10-01T23:30:00Z")
    assertEquals(date, PlanningTiming.Timed(instant).dateIn(zone))
    assertEquals(date.minusDays(1), PlanningTiming.Timed(instant).dateIn(ZoneId.of("UTC")))
    assertEquals(date, PlanningTiming.DateOnly(date).dateIn(ZoneId.of("Pacific/Honolulu")))
    assertEquals(date, PlanningTiming.DateOnly(date).dateIn(ZoneId.of("Pacific/Kiritimati")))
  }

  @Test
  fun itemRetainsSourceIdentityAndDisplayData() {
    val source = PlanningSource.Appointment("abc")
    val timing = PlanningTiming.Timed(start)
    val item = PlanningItem(source, timing, "Consultation", "Room 3")
    assertEquals(source, item.source)
    assertEquals(timing, item.timing)
    assertEquals("Consultation", item.title)
    assertEquals("Room 3", item.subtitle)
    assertNull(PlanningItem(source, timing, "Consultation").subtitle)
    assertNull(item.frequency)
  }

  @Test
  fun occurrenceKeysDistinguishSourcesTimesAndCalendarDays() {
    val timed = PlanningTiming.Timed(start)
    val appointment = PlanningItem(PlanningSource.Appointment("same"), timed, "Appointment")
    val event = PlanningItem(PlanningSource.Event("same"), timed, "Event")
    val medication =
        PlanningItem(
            PlanningSource.Medication("p", "same"),
            PlanningTiming.DateOnly(date),
            "Medication",
            frequency = "As needed",
        )
    val nextDay = medication.copy(timing = PlanningTiming.DateOnly(date.plusDays(1)))
    val nextTime = event.copy(timing = PlanningTiming.Timed(start.plusSeconds(1)))
    assertEquals(
        5,
        listOf(appointment, event, medication, nextDay, nextTime).map { it.key }.distinct().size,
    )
    assertEquals(event.key, event.copy(title = "Changed", subtitle = "Elsewhere").key)
    assertEquals(medication.key, medication.copy(title = "Changed", frequency = "Twice daily").key)
    assertEquals("p", (medication.source as PlanningSource.Medication).prescriptionId)
    assertEquals("same", medication.source.medicationId)
    assertEquals("same", (event.source as PlanningSource.Event).eventId)
  }

  @Test
  fun sourceKeysDoNotCollideWhenIdsContainSeparators() {
    assertNotEquals(
        PlanningSource.Medication("a:b", "c").key,
        PlanningSource.Medication("a", "b:c").key,
    )
    assertNotEquals(
        PlanningSource.Medication("p1", "m").key,
        PlanningSource.Medication("p2", "m").key,
    )
  }

  @Test
  fun blankSourceIdentitiesAreRejected() {
    reject { PlanningSource.Appointment(" ") }
    reject { PlanningSource.Event("") }
    reject { PlanningSource.Medication(" ", "m") }
    reject { PlanningSource.Medication("p", "") }
  }

  @Test
  fun invalidTimingAndFrequencyCombinationsAreRejected() {
    val medication = PlanningSource.Medication("p", "m")
    val appointment = PlanningSource.Appointment("a")
    val event = PlanningSource.Event("e")
    reject { PlanningItem(medication, PlanningTiming.Timed(start), "Medication") }
    reject { PlanningItem(appointment, PlanningTiming.DateOnly(date), "Appointment") }
    reject { PlanningItem(event, PlanningTiming.DateOnly(date), "Event") }
    reject {
      PlanningItem(appointment, PlanningTiming.Timed(start), "Appointment", frequency = "Daily")
    }
    reject { PlanningItem(event, PlanningTiming.Timed(start), "Event", frequency = "Daily") }
  }
}
