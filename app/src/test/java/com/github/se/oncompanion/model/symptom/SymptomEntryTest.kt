package com.github.se.oncompanion.model.symptom

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymptomEntryTest {

  private val valid =
      SymptomEntry(
          type = SymptomType.FATIGUE,
          intensity = 5,
          occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
      )

  private val other = valid.copy(type = SymptomType.OTHER, otherLabel = "Hiccups")

  @Test
  fun constantsHaveSpecifiedValues() {
    assertEquals(1, SymptomEntry.MIN_INTENSITY)
    assertEquals(10, SymptomEntry.MAX_INTENSITY)
    assertEquals(50, SymptomEntry.MAX_OTHER_LABEL_LENGTH)
    assertEquals(1000, SymptomEntry.MAX_NOTES_LENGTH)
    assertEquals(Duration.ofMinutes(5), SymptomEntry.MAX_CLOCK_DRIFT)
  }

  @Test
  fun optionalFieldsDefaultToEmpty() {
    assertEquals("", valid.id)
    assertNull(valid.otherLabel)
    assertNull(valid.notes)
    assertNull(valid.createdAt)
  }

  @Test
  fun typesMatchTheFigmaList() {
    assertEquals(
        listOf(
            SymptomType.FATIGUE,
            SymptomType.NAUSEA,
            SymptomType.PAIN,
            SymptomType.APPETITE_LOSS,
            SymptomType.SLEEP_PROBLEMS,
            SymptomType.BREATHLESSNESS,
            SymptomType.OTHER,
        ),
        SymptomType.entries,
    )
  }

  @Test
  fun everyListedTypeWithoutLabelIsValid() {
    SymptomType.entries
        .filter { it != SymptomType.OTHER }
        .forEach { assertTrue("$it", valid.copy(type = it).isValid()) }
  }

  @Test
  fun fullEntryIsValid() {
    assertTrue(valid.copy(id = "s1", notes = "After lunch", createdAt = Instant.now()).isValid())
  }

  @Test
  fun intensityBoundaries() {
    assertTrue(valid.copy(intensity = 1).isValid())
    assertTrue(valid.copy(intensity = 10).isValid())
    assertFalse(valid.copy(intensity = 0).isValid())
    assertFalse(valid.copy(intensity = 11).isValid())
    assertFalse(valid.copy(intensity = -1).isValid())
  }

  @Test
  fun otherNeedsANonBlankLabel() {
    assertTrue(other.isValid())
    assertFalse(other.copy(otherLabel = null).isValid())
    assertFalse(other.copy(otherLabel = "").isValid())
    assertFalse(other.copy(otherLabel = "   ").isValid())
  }

  @Test
  fun otherLabelLengthBoundary() {
    assertTrue(other.copy(otherLabel = "a".repeat(50)).isValid())
    assertFalse(other.copy(otherLabel = "a".repeat(51)).isValid())
  }

  @Test
  fun listedTypeWithLabelIsInvalid() {
    assertFalse(valid.copy(otherLabel = "Hiccups").isValid())
    assertFalse(valid.copy(otherLabel = "").isValid())
  }

  @Test
  fun occurredAt_canBeInThePast_orUpTo5MinutesAhead() {
    val now = valid.occurredAt

    assertTrue(valid.isValid(now))
    assertTrue(valid.isValid(now.plus(Duration.ofDays(400))))
    assertTrue(valid.isValid(now.minus(Duration.ofMinutes(5))))
    assertFalse(valid.isValid(now.minus(Duration.ofMinutes(5)).minusSeconds(1)))
    assertFalse(valid.isValid(now.minus(Duration.ofDays(1))))
  }

  @Test
  fun isValid_usesTheCurrentTimeByDefault() {
    assertTrue(valid.copy(occurredAt = Instant.now()).isValid())
    assertFalse(valid.copy(occurredAt = Instant.now().plus(Duration.ofHours(1))).isValid())
  }

  @Test
  fun notesLengthBoundary() {
    assertTrue(valid.copy(notes = "n".repeat(1000)).isValid())
    assertFalse(valid.copy(notes = "n".repeat(1001)).isValid())
  }
}
