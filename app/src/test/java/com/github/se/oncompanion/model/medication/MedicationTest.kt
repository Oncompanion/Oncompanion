package com.github.se.oncompanion.model.medication

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationTest {

  private val valid =
      Medication(
          id = "med-1",
          prescriptionId = "presc-1",
          name = "Ondansetron 8 mg",
          startDate = LocalDate.of(2026, 9, 29),
      )

  @Test
  fun constantsHaveSpecifiedValues() {
    assertEquals(100, Medication.MAX_TEXT_LENGTH)
    assertEquals(3650, Medication.MAX_DURATION_DAYS)
  }

  @Test
  fun optionalFieldsDefaultToNull() {
    assertNull(valid.dosage)
    assertNull(valid.frequency)
    assertNull(valid.durationDays)
  }

  @Test
  fun minimalMedicationIsValid() {
    assertTrue(valid.isValid())
  }

  @Test
  fun fullMedicationIsValid() {
    assertTrue(
        valid.copy(dosage = "1 tablet", frequency = "Twice a day", durationDays = 5).isValid()
    )
  }

  @Test
  fun blankIdsAreInvalid() {
    assertFalse(valid.copy(id = "").isValid())
    assertFalse(valid.copy(id = "  ").isValid())
    assertFalse(valid.copy(prescriptionId = "").isValid())
    assertFalse(valid.copy(prescriptionId = "  ").isValid())
  }

  @Test
  fun blankNameIsInvalid() {
    assertFalse(valid.copy(name = "").isValid())
    assertFalse(valid.copy(name = "  ").isValid())
  }

  @Test
  fun textFieldsAreLimitedInLength() {
    val max = "a".repeat(Medication.MAX_TEXT_LENGTH)
    val tooLong = max + "a"
    assertTrue(valid.copy(name = max, dosage = max, frequency = max).isValid())
    assertFalse(valid.copy(name = tooLong).isValid())
    assertFalse(valid.copy(dosage = tooLong).isValid())
    assertFalse(valid.copy(frequency = tooLong).isValid())
  }

  @Test
  fun durationMustBeBetweenOneDayAndTheMaximum() {
    assertTrue(valid.copy(durationDays = 1).isValid())
    assertTrue(valid.copy(durationDays = Medication.MAX_DURATION_DAYS).isValid())
    assertFalse(valid.copy(durationDays = 0).isValid())
    assertFalse(valid.copy(durationDays = -3).isValid())
    assertFalse(valid.copy(durationDays = Medication.MAX_DURATION_DAYS + 1).isValid())
  }

  @Test
  fun durationIsValidWithoutAnEndOrWithinTheLimits() {
    assertTrue(Medication.isValidDuration(null))
    assertTrue(Medication.isValidDuration(1))
    assertTrue(Medication.isValidDuration(Medication.MAX_DURATION_DAYS))
    assertFalse(Medication.isValidDuration(0))
    assertFalse(Medication.isValidDuration(-1))
    assertFalse(Medication.isValidDuration(Medication.MAX_DURATION_DAYS + 1))
  }
}
