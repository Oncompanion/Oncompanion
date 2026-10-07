package com.github.se.oncompanion.model.symptom

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {

  @Test
  fun everyIntensityHasItsBand() {
    val expected =
        mapOf(
            1 to Severity.MILD,
            2 to Severity.MILD,
            3 to Severity.MILD,
            4 to Severity.MODERATE,
            5 to Severity.MODERATE,
            6 to Severity.MODERATE,
            7 to Severity.SEVERE,
            8 to Severity.SEVERE,
            9 to Severity.SEVERE,
            10 to Severity.SEVERE,
        )

    val actual =
        (SymptomEntry.MIN_INTENSITY..SymptomEntry.MAX_INTENSITY).associateWith { Severity.of(it) }

    assertEquals(expected, actual)
  }

  @Test
  fun bandsAreInOrder() {
    assertEquals(listOf(Severity.MILD, Severity.MODERATE, Severity.SEVERE), Severity.entries)
    assertEquals(3, Severity.MAX_MILD)
    assertEquals(6, Severity.MAX_MODERATE)
  }

  @Test
  fun entrySeverity_comesFromItsIntensity() {
    val entry = SymptomEntry(type = SymptomType.PAIN, intensity = 8, occurredAt = Instant.EPOCH)

    assertEquals(Severity.SEVERE, entry.severity)
    assertEquals(Severity.MILD, entry.copy(intensity = 1).severity)
  }
}
