// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detail follows recorded snapshots and access, rather than retaining a Planning row's text.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationDetailViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeMedicationRepository()
  private val auth = FakeAuthRepository { AuthUser(it) }
  private val circle = FakeCareCircleRepository()
  private val day = LocalDate.of(2026, 11, 10)
  private val medication = Medication("m", "p", "Medication A", "1 tablet", "As recorded", day, 5)
  private val prescription =
      Prescription("p", "Dr. Example", day, listOf(medication.copy(id = "other"), medication))

  /** Uses virtual time for ViewModel observation and retries. */
  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  /** Restores Main so this suite does not change other tests. */
  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() =
      MedicationDetailViewModel("p", "m", auth, repository, careCircleRepository = circle)

  /** All lookup outcomes use the IDs, including missing and rejected data. */
  @Test
  fun snapshotStates_preserveOptionalFieldsAndSelectTheCorrectMedication() = runTest {
    auth.signInWithGoogle("patient")
    val vm = viewModel()
    assertEquals(MedicationDetailUiState.Loading, vm.uiState.value)
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
    val optional = medication.copy(dosage = null, frequency = null, durationDays = null)
    for ((item, expected) in
        listOf(
            prescription to medication,
            prescription.copy(medications = listOf(optional)) to optional,
            prescription.copy(medications = listOf(medication.copy(id = "different"))) to null,
            prescription.copy(confirmation = PrescriptionConfirmation.REJECTED) to null,
        )) {
      repository.seed("patient", item)
      advanceUntilIdle()
      if (expected == null) assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
      else assertEquals(expected, (vm.uiState.value as MedicationDetailUiState.Loaded).medication)
    }
  }

  /** Changes to the containing document refresh or remove the medication without a reload. */
  @Test
  fun updatesDeletionAndAccountChanges_replacePrivateContent() = runTest {
    auth.signInWithGoogle("patient")
    repository.seed("patient", prescription)
    val vm = viewModel()
    advanceUntilIdle()
    val corrected = medication.copy(name = "Corrected", dosage = "Recorded dosage")
    repository.updatePrescription("patient", prescription.copy(medications = listOf(corrected)))
    advanceUntilIdle()
    assertEquals(corrected, (vm.uiState.value as MedicationDetailUiState.Loaded).medication)
    repository.deletePrescription("patient", "p")
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
    repository.seed("patient", prescription)
    advanceUntilIdle()
    auth.signOut()
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
    assertEquals(0, repository.prescriptionObservers)
    auth.signInWithGoogle("another")
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
  }

  /**
   * Both initial and running read errors hide stale actions; repeated retries keep one listener.
   */
  @Test
  fun readFailures_retryWithoutDuplicatingObservers() = runTest {
    auth.signInWithGoogle("patient")
    repository.seed("patient", prescription)
    repository.readError = IllegalStateException()
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Error, vm.uiState.value)
    repository.readError = null
    repeat(2) {
      vm.retry()
      advanceUntilIdle()
      assertEquals(1, repository.prescriptionObservers)
    }
    assertTrue(vm.uiState.value is MedicationDetailUiState.Loaded)
    repository.failRunningReads(IllegalStateException())
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Error, vm.uiState.value)
    vm.retry()
    advanceUntilIdle()
    assertTrue(vm.uiState.value is MedicationDetailUiState.Loaded)
  }

  /** An open caregiver detail loses its content when sharing or membership is revoked. */
  @Test
  fun caregiverAccess_isLiveAndNeverReadsTheCaregiversOwnPrescription() = runTest {
    val followed = FollowedPatient(CarePermission.PRESCRIPTIONS)
    repository.seed("patient", prescription)
    val vm =
        MedicationDetailViewModel(
            "p",
            "m",
            followed.auth,
            repository,
            "patient",
            followed.sectionAccess,
            circle,
        )
    advanceUntilIdle()
    assertTrue(vm.uiState.value is MedicationDetailUiState.Loaded)
    followed.shares()
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.NotShared, vm.uiState.value)
    assertEquals(0, repository.prescriptionObservers)
    followed.shares(CarePermission.PRESCRIPTIONS)
    advanceUntilIdle()
    assertTrue(vm.uiState.value is MedicationDetailUiState.Loaded)
    followed.removesTheCaregiver()
    advanceUntilIdle()
    assertEquals(MedicationDetailUiState.Unavailable, vm.uiState.value)
  }

  /**
   * Pending data retains its state and attribution; the patient's circle rename takes precedence.
   */
  @Test
  fun pendingPrescription_keepsAttributionAndUpdatesTheMembersName() = runTest {
    auth.signInWithGoogle("patient")
    repository.seed(
        "patient",
        prescription.copy(
            addedBy = "caregiver",
            addedByName = "Stored",
            confirmation = PrescriptionConfirmation.PENDING,
        ),
    )
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(
        "Stored",
        (vm.uiState.value as MedicationDetailUiState.Loaded).prescription.addedByName,
    )
    circle.setMembers(
        "patient",
        listOf(
            com.github.se.oncompanion.model.carecircle.CareCircleMember(
                uid = "caregiver",
                firstName = "Renamed",
            )
        ),
    )
    advanceUntilIdle()
    val shown = (vm.uiState.value as MedicationDetailUiState.Loaded).prescription
    assertEquals("Renamed", shown.addedByName)
    assertFalse(shown.isConfirmed)
  }
}
