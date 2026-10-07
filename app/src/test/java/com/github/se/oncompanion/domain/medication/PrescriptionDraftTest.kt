package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionDraftTest {

  private val date = LocalDate.of(2026, 9, 29)
  private val medication = MedicationDraft(name = "Ondansetron 8 mg", startDate = date)
  private val draft = PrescriptionDraft(prescribedOn = date, medications = listOf(medication))

  /** A source of new IDs that gives [ids] in order, and fails if more are asked for. */
  private fun newIds(vararg ids: String): () -> String = ids.iterator()::next

  @Test
  fun optionalFieldsStartEmpty() {
    assertEquals("", draft.prescribedBy)
    assertEquals("", medication.dosage)
    assertEquals("", medication.frequency)
    assertNull(medication.durationDays)
    assertNull(medication.id)
  }

  @Test
  fun draftWithOnlyRequiredFieldsIsValid() {
    assertTrue(medication.isValid())
    assertTrue(draft.isValid())
  }

  @Test
  fun fullDraftIsValid() {
    val full =
        PrescriptionDraft(
            prescribedBy = "Dr. Martin · Oncology",
            prescribedOn = date,
            medications =
                listOf(
                    medication.copy(
                        dosage = "1 tablet",
                        frequency = "Twice a day",
                        durationDays = 5,
                    ),
                    MedicationDraft(name = "Dexamethasone 4 mg", startDate = date.plusDays(1)),
                ),
        )
    assertTrue(full.isValid())
  }

  @Test
  fun medicationNameIsRequired() {
    assertFalse(medication.copy(name = "").isValid())
    assertFalse(medication.copy(name = "   ").isValid())
  }

  @Test
  fun medicationTextIsLimitedInLength() {
    val longest = "a".repeat(Medication.MAX_TEXT_LENGTH)
    assertTrue(medication.copy(name = longest, dosage = longest, frequency = longest).isValid())
    assertFalse(medication.copy(name = longest + "a").isValid())
    assertFalse(medication.copy(dosage = longest + "a").isValid())
    assertFalse(medication.copy(frequency = longest + "a").isValid())
  }

  @Test
  fun spacesAroundTextDoNotCountInItsLength() {
    val longest = "a".repeat(Medication.MAX_TEXT_LENGTH)
    assertTrue(medication.copy(name = "  $longest  ").isValid())
    assertTrue(
        draft
            .copy(prescribedBy = " ${"a".repeat(Prescription.MAX_PRESCRIBED_BY_LENGTH)} ")
            .isValid()
    )
  }

  @Test
  fun durationIsBetweenOneDayAndTheMaximum() {
    assertTrue(medication.copy(durationDays = 1).isValid())
    assertTrue(medication.copy(durationDays = Medication.MAX_DURATION_DAYS).isValid())
    assertFalse(medication.copy(durationDays = 0).isValid())
    assertFalse(medication.copy(durationDays = -3).isValid())
    assertFalse(medication.copy(durationDays = Medication.MAX_DURATION_DAYS + 1).isValid())
  }

  @Test
  fun prescribedByIsLimitedInLength() {
    val longest = "a".repeat(Prescription.MAX_PRESCRIBED_BY_LENGTH)
    assertTrue(draft.copy(prescribedBy = longest).isValid())
    assertFalse(draft.copy(prescribedBy = longest + "a").isValid())
  }

  @Test
  fun prescriptionNeedsAtLeastOneMedication() {
    assertFalse(draft.copy(medications = emptyList()).isValid())
  }

  @Test
  fun prescriptionHasAtMostTheMaximumNumberOfMedications() {
    val most = List(Prescription.MAX_MEDICATIONS) { medication }
    assertTrue(draft.copy(medications = most).isValid())
    assertFalse(draft.copy(medications = most + medication).isValid())
  }

  @Test
  fun oneInvalidMedicationMakesThePrescriptionInvalid() {
    assertFalse(draft.copy(medications = listOf(medication.copy(name = ""))).isValid())
    assertFalse(
        draft.copy(medications = listOf(medication, medication.copy(durationDays = 0))).isValid()
    )
  }

  @Test
  fun toPrescriptionKeepsWhatWasEntered() {
    val full =
        PrescriptionDraft(
            prescribedBy = "Dr. Martin",
            prescribedOn = date,
            medications =
                listOf(
                    MedicationDraft(
                        name = "Ondansetron 8 mg",
                        dosage = "1 tablet",
                        frequency = "Twice a day",
                        startDate = date.plusDays(1),
                        durationDays = 5,
                    )
                ),
        )

    val expected =
        Prescription(
            id = "presc-1",
            prescribedBy = "Dr. Martin",
            prescribedOn = date,
            medications =
                listOf(
                    Medication(
                        id = "med-1",
                        prescriptionId = "presc-1",
                        name = "Ondansetron 8 mg",
                        dosage = "1 tablet",
                        frequency = "Twice a day",
                        startDate = date.plusDays(1),
                        durationDays = 5,
                    )
                ),
        )
    assertEquals(expected, full.toPrescription("presc-1", newIds("med-1")))
  }

  @Test
  fun toPrescriptionGivesNewMedicationsAnIdInOrderAndLinksThem() {
    val two = draft.copy(medications = listOf(medication, medication.copy(name = "Dexamethasone")))

    val prescription = two.toPrescription("presc-1", newIds("med-1", "med-2"))

    assertEquals("presc-1", prescription.id)
    assertEquals(listOf("med-1", "med-2"), prescription.medications.map { it.id })
    assertEquals(
        listOf("Ondansetron 8 mg", "Dexamethasone"),
        prescription.medications.map { it.name },
    )
    assertTrue(prescription.medications.all { it.prescriptionId == "presc-1" })
    assertNull(prescription.createdAt)
  }

  @Test
  fun medicationThatAlreadyHasAnIdKeepsIt() {
    val saved = medication.copy(id = "med-7", name = "Dexamethasone")
    val added = medication.copy(name = "Paracetamol")
    val edited = draft.copy(medications = listOf(saved, added))

    val prescription = edited.toPrescription("presc-1", newIds("new-1"))

    assertEquals(listOf("med-7", "new-1"), prescription.medications.map { it.id })
    assertEquals(listOf("Dexamethasone", "Paracetamol"), prescription.medications.map { it.name })
  }

  @Test
  fun medicationsKeepTheirIdWhenAnotherOneIsRemovedOrTheyAreReordered() {
    val first = medication.copy(id = "med-1", name = "Ondansetron")
    val second = medication.copy(id = "med-2", name = "Dexamethasone")

    val afterRemoval = draft.copy(medications = listOf(second)).toPrescription("presc-1", newIds())
    val reordered =
        draft.copy(medications = listOf(second, first)).toPrescription("presc-1", newIds())

    assertEquals("med-2", afterRemoval.medications.single().id)
    assertEquals("Dexamethasone", afterRemoval.medications.single().name)
    assertEquals(
        listOf("med-2" to "Dexamethasone", "med-1" to "Ondansetron"),
        reordered.medications.map { it.id to it.name },
    )
  }

  @Test
  fun draftWithSavedAndNewMedicationsIsValid() {
    val mixed = draft.copy(medications = listOf(medication.copy(id = "med-7"), medication))
    assertTrue(mixed.isValid())
  }

  @Test
  fun twoMedicationsWithTheSameIdAreInvalid() {
    val twice = medication.copy(id = "med-7")
    assertFalse(draft.copy(medications = listOf(twice, twice)).isValid())
  }

  @Test
  fun toPrescriptionRemovesTheSpacesAroundText() {
    val spaced =
        draft.copy(
            prescribedBy = "  Dr. Martin ",
            medications =
                listOf(
                    medication.copy(
                        name = " Ondansetron 8 mg  ",
                        dosage = " 1 tablet ",
                        frequency = " Daily ",
                    )
                ),
        )

    val prescription = spaced.toPrescription("presc-1", newIds("med-1"))

    assertEquals("Dr. Martin", prescription.prescribedBy)
    assertEquals("Ondansetron 8 mg", prescription.medications[0].name)
    assertEquals("1 tablet", prescription.medications[0].dosage)
    assertEquals("Daily", prescription.medications[0].frequency)
  }

  @Test
  fun toPrescriptionStoresNothingForOptionalFieldsLeftEmpty() {
    val blank =
        draft.copy(
            prescribedBy = "  ",
            medications = listOf(medication.copy(dosage = "", frequency = "  ")),
        )

    val prescription = blank.toPrescription("presc-1", newIds("med-1"))

    assertNull(prescription.prescribedBy)
    assertNull(prescription.medications[0].dosage)
    assertNull(prescription.medications[0].frequency)
    assertNull(prescription.medications[0].durationDays)
  }

  @Test
  fun aValidDraftMakesAValidPrescription() {
    val prescription = draft.toPrescription("presc-1", newIds("med-1"))
    assertTrue(prescription.isValid())
  }
}
