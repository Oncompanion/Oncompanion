package com.github.se.oncompanion.model.planning

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningContractTest {
  private val start = Instant.parse("2026-10-02T00:00:00Z")
  private val end = Instant.parse("2026-10-03T00:00:00Z")

  @Test
  fun rangeIncludesStartButExcludesEnd() {
    val range = PlanningRange(start, end)
    assertTrue(start in range)
    assertTrue(start.plusSeconds(1) in range)
    assertFalse(start.minusSeconds(1) in range)
    assertFalse(end in range)
  }

  @Test(expected = IllegalArgumentException::class)
  fun emptyRangeIsRejected() {
    PlanningRange(start, start)
  }

  @Test(expected = IllegalArgumentException::class)
  fun reversedRangeIsRejected() {
    PlanningRange(end, start)
  }

  @Test
  fun itemRetainsSourceIdentityAndDisplayData() {
    val source = PlanningSource.Appointment("abc")
    val item = PlanningItem(source, start, "Consultation", "Room 3")
    assertEquals("appointment:abc", item.key)
    assertEquals(source, item.source)
    assertEquals(start, item.scheduledAt)
    assertEquals("Consultation", item.title)
    assertEquals("Room 3", item.subtitle)
    assertEquals(null, PlanningItem(source, start, "Consultation").subtitle)
  }

  @Test(expected = IllegalArgumentException::class)
  fun blankAppointmentIdentityIsRejected() {
    PlanningSource.Appointment(" ")
  }
}
