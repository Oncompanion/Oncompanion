package com.github.se.oncompanion.ui.planning

import java.time.LocalDate
import java.time.format.DateTimeParseException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningDatesTest {
  @Test
  fun formatsWithLeadingZeros() {
    assertEquals("02/10/2026", PlanningDates.format(LocalDate.of(2026, 10, 2)))
    assertEquals("01/02/0001", PlanningDates.format(LocalDate.of(1, 2, 1)))
  }

  @Test
  fun acceptsLeapDay() {
    assertEquals(LocalDate.of(2024, 2, 29), PlanningDates.parse("29/02/2024"))
  }

  @Test(expected = DateTimeParseException::class)
  fun rejectsInvalidLeapDay() {
    PlanningDates.parse("29/02/2026")
  }

  @Test(expected = DateTimeParseException::class)
  fun rejectsInvalidMonthDay() {
    PlanningDates.parse("31/04/2026")
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsMissingLeadingZero() {
    PlanningDates.parse("2/10/2026")
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsYearZero() {
    PlanningDates.parse("02/10/0000")
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsLongYear() {
    PlanningDates.format(LocalDate.of(10000, 1, 1))
  }
}
