package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageMedicationScheduleTest {

  private val uid = "patient-1"
  private val date = LocalDate.of(2026, 9, 29)
  private val draft =
      PrescriptionDraft(
          prescribedBy = " Dr. Martin ",
          prescribedOn = date,
          medications =
              listOf(
                  MedicationDraft(
                      name = "Ondansetron 8 mg",
                      dosage = "1 tablet",
                      frequency = "Twice a day",
                      startDate = date,
                      durationDays = 5,
                  ),
                  MedicationDraft(name = "Dexamethasone 4 mg", startDate = date.plusDays(1)),
              ),
      )

  private val repository = FakeMedicationRepository()
  private val manageMedicationSchedule = ManageMedicationSchedule(repository)

  @Test
  fun savesTheDraftAsAPrescriptionOfTheUser() = runTest {
    manageMedicationSchedule(uid, draft)

    val saved = repository.prescriptionsOf(uid).single()
    assertEquals("Dr. Martin", saved.prescribedBy)
    assertEquals(date, saved.prescribedOn)
    assertEquals(
        listOf("Ondansetron 8 mg", "Dexamethasone 4 mg"),
        saved.medications.map { it.name },
    )
    assertEquals("1 tablet", saved.medications[0].dosage)
    assertEquals("Twice a day", saved.medications[0].frequency)
    assertEquals(5, saved.medications[0].durationDays)
    assertEquals(date.plusDays(1), saved.medications[1].startDate)
    assertNull(saved.medications[1].durationDays)
  }

  @Test
  fun savedPrescriptionIsValid() = runTest {
    manageMedicationSchedule(uid, draft)

    assertTrue(repository.prescriptionsOf(uid).single().isValid())
  }

  @Test
  fun prescriptionAndMedicationsEachGetTheirOwnId() = runTest {
    manageMedicationSchedule(uid, draft)

    val saved = repository.prescriptionsOf(uid).single()
    val ids = saved.medications.map { it.id } + saved.id
    assertTrue(ids.all { it.isNotBlank() })
    assertEquals(ids.size, ids.distinct().size)
    assertTrue(saved.medications.all { it.prescriptionId == saved.id })
  }

  @Test
  fun savingTwiceKeepsBothPrescriptions() = runTest {
    manageMedicationSchedule(uid, draft)
    manageMedicationSchedule(uid, draft.copy(prescribedOn = date.plusDays(7)))

    val saved = repository.prescriptionsOf(uid)
    assertEquals(2, saved.size)
    val medicationIds = saved.flatMap { it.medications }.map { it.id }
    assertEquals(4, medicationIds.distinct().size)
  }

  @Test
  fun draftMadeFromASavedPrescriptionIsRefused() = runTest {
    // A first prescription, then a draft with its medication IDs sent to be added again
    manageMedicationSchedule(uid, draft)
    val first = repository.prescriptionsOf(uid).single()
    val fromSaved =
        draft.copy(
            medications =
                draft.medications.mapIndexed { position, medication ->
                  medication.copy(id = first.medications[position].id)
                }
        )

    val result = runCatching { manageMedicationSchedule(uid, fromSaved) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertEquals(listOf(first), repository.prescriptionsOf(uid))
  }

  @Test
  fun draftWithOneMedicationThatAlreadyHasAnIdIsRefused() = runTest {
    val mixed =
        draft.copy(
            medications = listOf(draft.medications[0], draft.medications[1].copy(id = "med-7"))
        )

    val result = runCatching { manageMedicationSchedule(uid, mixed) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
    // The fake numbers its IDs: none was used
    assertEquals("id-1", repository.newId())
  }

  @Test
  fun savesOnlyForTheGivenUser() = runTest {
    manageMedicationSchedule(uid, draft)

    assertTrue(repository.prescriptionsOf("patient-2").isEmpty())
  }

  @Test
  fun draftWithoutAMedicationNameIsRefusedAndNothingIsSaved() = runTest {
    val withoutName =
        draft.copy(medications = listOf(MedicationDraft(name = " ", startDate = date)))

    val result = runCatching { manageMedicationSchedule(uid, withoutName) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun draftWithoutMedicationsIsRefusedAndNothingIsSaved() = runTest {
    val result = runCatching {
      manageMedicationSchedule(uid, draft.copy(medications = emptyList()))
    }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun refusedDraftIsNotGivenAnyId() = runTest {
    val withoutName =
        draft.copy(medications = listOf(MedicationDraft(name = " ", startDate = date)))

    runCatching { manageMedicationSchedule(uid, withoutName) }

    // The fake numbers its IDs: the next one is still the first
    assertEquals("id-1", repository.newId())
  }

  @Test
  fun failureToSaveIsReported() = runTest {
    val failure = IOException("disk full")
    repository.writeError = failure

    val result = runCatching { manageMedicationSchedule(uid, draft) }

    assertEquals(failure, result.exceptionOrNull())
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }
}
