// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.domain.medication

import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    manageMedicationSchedule.add(uid, draft)

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
    manageMedicationSchedule.add(uid, draft)

    assertTrue(repository.prescriptionsOf(uid).single().isValid())
  }

  @Test
  fun prescriptionAndMedicationsEachGetTheirOwnId() = runTest {
    manageMedicationSchedule.add(uid, draft)

    val saved = repository.prescriptionsOf(uid).single()
    val ids = saved.medications.map { it.id } + saved.id
    assertTrue(ids.all { it.isNotBlank() })
    assertEquals(ids.size, ids.distinct().size)
    assertTrue(saved.medications.all { it.prescriptionId == saved.id })
  }

  @Test
  fun savingTwiceKeepsBothPrescriptions() = runTest {
    manageMedicationSchedule.add(uid, draft)
    manageMedicationSchedule.add(uid, draft.copy(prescribedOn = date.plusDays(7)))

    val saved = repository.prescriptionsOf(uid)
    assertEquals(2, saved.size)
    val medicationIds = saved.flatMap { it.medications }.map { it.id }
    assertEquals(4, medicationIds.distinct().size)
  }

  @Test
  fun draftMadeFromASavedPrescriptionIsRefused() = runTest {
    // A first prescription, then a draft with its medication IDs sent to be added again
    manageMedicationSchedule.add(uid, draft)
    val first = repository.prescriptionsOf(uid).single()
    val fromSaved =
        draft.copy(
            medications =
                draft.medications.mapIndexed { position, medication ->
                  medication.copy(id = first.medications[position].id)
                }
        )

    val result = runCatching { manageMedicationSchedule.add(uid, fromSaved) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertEquals(listOf(first), repository.prescriptionsOf(uid))
  }

  @Test
  fun draftWithOneMedicationThatAlreadyHasAnIdIsRefused() = runTest {
    val mixed =
        draft.copy(
            medications = listOf(draft.medications[0], draft.medications[1].copy(id = "med-7"))
        )

    val result = runCatching { manageMedicationSchedule.add(uid, mixed) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
    // The fake numbers its IDs: none was used
    assertEquals("id-1", repository.newId())
  }

  @Test
  fun savesOnlyForTheGivenUser() = runTest {
    manageMedicationSchedule.add(uid, draft)

    assertTrue(repository.prescriptionsOf("patient-2").isEmpty())
  }

  @Test
  fun draftWithoutAMedicationNameIsRefusedAndNothingIsSaved() = runTest {
    val withoutName =
        draft.copy(medications = listOf(MedicationDraft(name = " ", startDate = date)))

    val result = runCatching { manageMedicationSchedule.add(uid, withoutName) }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun draftWithoutMedicationsIsRefusedAndNothingIsSaved() = runTest {
    val result = runCatching {
      manageMedicationSchedule.add(uid, draft.copy(medications = emptyList()))
    }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }

  @Test
  fun refusedDraftIsNotGivenAnyId() = runTest {
    val withoutName =
        draft.copy(medications = listOf(MedicationDraft(name = " ", startDate = date)))

    runCatching { manageMedicationSchedule.add(uid, withoutName) }

    // The fake numbers its IDs: the next one is still the first
    assertEquals("id-1", repository.newId())
  }

  @Test
  fun failureToSaveIsReported() = runTest {
    val failure = IOException("disk full")
    repository.writeError = failure

    val result = runCatching { manageMedicationSchedule.add(uid, draft) }

    assertEquals(failure, result.exceptionOrNull())
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
  }

  // ---------- Updating ----------

  /** Saves [draft] and returns it as stored. */
  private suspend fun savedPrescription(): Prescription {
    manageMedicationSchedule.add(uid, draft)
    return repository.prescriptionsOf(uid).single()
  }

  @Test
  fun updateReplacesTheContentAndKeepsTheIds() = runTest {
    val saved = savedPrescription()
    val edited =
        saved.toDraft().copy(prescribedBy = "Dr. House", prescribedOn = date.minusDays(2)).let {
          it.copy(medications = it.medications.map { m -> m.copy(dosage = "2 tablets") })
        }

    manageMedicationSchedule.update(uid, saved.id, edited)

    val updated = repository.prescriptionsOf(uid).single()
    assertEquals(saved.id, updated.id)
    assertEquals(saved.createdAt, updated.createdAt)
    assertEquals("Dr. House", updated.prescribedBy)
    assertEquals(date.minusDays(2), updated.prescribedOn)
    assertEquals(saved.medications.map { it.id }, updated.medications.map { it.id })
    assertEquals(listOf("2 tablets", "2 tablets"), updated.medications.map { it.dosage })
    assertTrue(updated.isValid())
  }

  @Test
  fun updateGivesAnAddedMedicationItsOwnId() = runTest {
    val saved = savedPrescription()
    val added = MedicationDraft(name = "Paracetamol", startDate = date)
    val edited = saved.toDraft().let { it.copy(medications = it.medications + added) }

    manageMedicationSchedule.update(uid, saved.id, edited)

    val updated = repository.prescriptionsOf(uid).single()
    assertEquals(saved.medications.map { it.id }, updated.medications.take(2).map { it.id })
    val new = updated.medications[2]
    assertEquals("Paracetamol", new.name)
    assertTrue(new.id.isNotBlank())
    assertFalse(new.id in saved.medications.map { it.id } + saved.id)
    assertEquals(saved.id, new.prescriptionId)
  }

  @Test
  fun updateRemovesTheMedicationsLeftOut() = runTest {
    val saved = savedPrescription()
    val edited = saved.toDraft().let { it.copy(medications = it.medications.drop(1)) }

    manageMedicationSchedule.update(uid, saved.id, edited)

    val updated = repository.prescriptionsOf(uid).single()
    assertEquals(listOf(saved.medications[1]), updated.medications)
  }

  @Test
  fun invalidDraftIsNotUpdatedAndNothingChanges() = runTest {
    val saved = savedPrescription()
    val nextId = saved.medications.size + 2
    val withoutName =
        saved.toDraft().let {
          it.copy(medications = it.medications.map { m -> m.copy(name = " ") })
        }

    val result = runCatching { manageMedicationSchedule.update(uid, saved.id, withoutName) }
    val empty = runCatching {
      manageMedicationSchedule.update(uid, saved.id, draft.copy(medications = emptyList()))
    }

    assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    assertTrue(empty.exceptionOrNull() is IllegalArgumentException)
    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
    // The fake numbers its IDs: none was used
    assertEquals("id-$nextId", repository.newId())
  }

  @Test
  fun updatingAPrescriptionThatDoesNotExistSavesNothing() = runTest {
    val saved = savedPrescription()

    val unknown = manageMedicationSchedule.update(uid, "unknown", draft)
    val otherUser = manageMedicationSchedule.update("patient-2", saved.id, saved.toDraft())

    assertFalse(unknown)
    assertFalse(otherUser)
    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
    assertTrue(repository.prescriptionsOf("patient-2").isEmpty())
  }

  @Test
  fun updateSaysThatThePrescriptionStillExisted() = runTest {
    val saved = savedPrescription()

    val existed =
        manageMedicationSchedule.update(uid, saved.id, saved.toDraft().copy(prescribedBy = "Other"))

    assertTrue(existed)
    assertEquals("Other", repository.prescriptionsOf(uid).single().prescribedBy)
  }

  @Test
  fun updatingAPrescriptionDeletedInTheMeantimeWritesNothingAndSaysSo() = runTest {
    val saved = savedPrescription()
    val nextId = saved.medications.size + 2
    // The form was opened on the saved prescription, with a medication added
    val edited =
        saved.toDraft().let {
          it.copy(medications = it.medications + MedicationDraft(name = "New", startDate = date))
        }
    manageMedicationSchedule.delete(uid, saved.id)

    val existed = manageMedicationSchedule.update(uid, saved.id, edited)

    assertFalse(existed)
    assertTrue(repository.prescriptionsOf(uid).isEmpty())
    // The fake numbers its IDs: none was used for the added medication
    assertEquals("id-$nextId", repository.newId())
  }

  @Test
  fun failureToCheckThePrescriptionIsReportedAndNothingChanges() = runTest {
    val saved = savedPrescription()
    val failure = IOException("unavailable")
    repository.readError = failure

    val result = runCatching {
      manageMedicationSchedule.update(uid, saved.id, saved.toDraft().copy(prescribedBy = "Other"))
    }

    assertEquals(failure, result.exceptionOrNull())
    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
  }

  @Test
  fun failureToUpdateIsReportedAndNothingChanges() = runTest {
    val saved = savedPrescription()
    val failure = IOException("disk full")
    repository.writeError = failure

    val result = runCatching {
      manageMedicationSchedule.update(uid, saved.id, saved.toDraft().copy(prescribedBy = "Other"))
    }

    assertEquals(failure, result.exceptionOrNull())
    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
  }

  // ---------- Deleting ----------

  @Test
  fun deleteRemovesThePrescriptionAndItsMedications() = runTest {
    val saved = savedPrescription()
    manageMedicationSchedule.add(uid, draft.copy(prescribedOn = date.plusDays(7)))

    manageMedicationSchedule.delete(uid, saved.id)

    val left = repository.prescriptionsOf(uid).single()
    assertEquals(date.plusDays(7), left.prescribedOn)
    assertTrue(left.medications.none { it.prescriptionId == saved.id })
  }

  @Test
  fun deleteOnlyTouchesTheGivenUserAndPrescription() = runTest {
    val saved = savedPrescription()

    manageMedicationSchedule.delete("patient-2", saved.id)
    manageMedicationSchedule.delete(uid, "unknown")

    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
  }

  @Test
  fun failureToDeleteIsReportedAndNothingIsDeleted() = runTest {
    val saved = savedPrescription()
    val failure = IOException("disk full")
    repository.writeError = failure

    val result = runCatching { manageMedicationSchedule.delete(uid, saved.id) }

    assertEquals(failure, result.exceptionOrNull())
    assertEquals(listOf(saved), repository.prescriptionsOf(uid))
  }
}
