package com.github.se.oncompanion.model.medication

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionTest {

  private val date = LocalDate.of(2026, 9, 29)

  private fun medication(id: String, prescriptionId: String = "presc-1") =
      Medication(
          id = id,
          prescriptionId = prescriptionId,
          name = "Ondansetron 8 mg",
          startDate = date,
      )

  private val valid =
      Prescription(id = "presc-1", prescribedOn = date, medications = listOf(medication("med-1")))

  @Test
  fun constantsHaveSpecifiedValues() {
    assertEquals(100, Prescription.MAX_PRESCRIBED_BY_LENGTH)
    assertEquals(20, Prescription.MAX_MEDICATIONS)
  }

  @Test
  fun optionalFieldsDefaultToNull() {
    assertNull(valid.prescribedBy)
    assertNull(valid.createdAt)
  }

  @Test
  fun minimalPrescriptionIsValid() {
    assertTrue(valid.isValid())
  }

  @Test
  fun fullPrescriptionIsValid() {
    assertTrue(
        valid
            .copy(
                prescribedBy = "Dr. Martin · Oncology",
                medications = listOf(medication("med-1"), medication("med-2")),
                createdAt = Instant.now(),
            )
            .isValid()
    )
  }

  @Test
  fun blankIdIsInvalid() {
    assertFalse(valid.copy(id = "").isValid())
    assertFalse(valid.copy(id = "  ").isValid())
  }

  @Test
  fun prescribedByIsLimitedInLength() {
    val max = "a".repeat(Prescription.MAX_PRESCRIBED_BY_LENGTH)
    assertTrue(valid.copy(prescribedBy = max).isValid())
    assertFalse(valid.copy(prescribedBy = max + "a").isValid())
  }

  @Test
  fun needsAtLeastOneMedication() {
    assertFalse(valid.copy(medications = emptyList()).isValid())
  }

  @Test
  fun numberOfMedicationsIsLimited() {
    val max = (1..Prescription.MAX_MEDICATIONS).map { medication("med-$it") }
    assertTrue(valid.copy(medications = max).isValid())
    assertFalse(valid.copy(medications = max + medication("one-too-many")).isValid())
  }

  @Test
  fun invalidMedicationMakesThePrescriptionInvalid() {
    val unnamed = medication("med-2").copy(name = "")
    assertFalse(valid.copy(medications = listOf(medication("med-1"), unnamed)).isValid())
  }

  @Test
  fun medicationsMustBelongToThePrescription() {
    val other = medication("med-2", prescriptionId = "presc-2")
    assertFalse(valid.copy(medications = listOf(medication("med-1"), other)).isValid())
  }

  @Test
  fun medicationIdsMustBeUnique() {
    assertFalse(
        valid.copy(medications = listOf(medication("med-1"), medication("med-1"))).isValid()
    )
  }
}
