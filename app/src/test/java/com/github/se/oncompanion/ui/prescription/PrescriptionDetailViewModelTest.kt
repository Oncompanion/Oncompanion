// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.carecircle.FollowedPatient
import com.github.se.oncompanion.domain.carecircle.followedPatientAccess
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.model.medication.PrescriptionConfirmation
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.DeletePhase
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides android.util.Log, used when a read fails
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PrescriptionDetailViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val repository = FakeMedicationRepository()

  private val chemo =
      Prescription(
          id = "chemo",
          prescribedBy = "Dr. Martin",
          prescribedOn = LocalDate.of(2026, 9, 29),
          medications =
              listOf(
                  Medication(
                      id = "m1",
                      prescriptionId = "chemo",
                      name = "Ondansetron 8 mg",
                      dosage = "1 tablet",
                      frequency = "Twice a day",
                      startDate = LocalDate.of(2026, 9, 29),
                      durationDays = 5,
                  )
              ),
      )

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(
      id: String = "chemo",
      auth: AuthRepository = signedIn(),
      medications: MedicationRepository = repository,
  ) = PrescriptionDetailViewModel(id, auth, medications)

  /** A ViewModel showing [chemo], with the deletion confirmation open. */
  private fun TestScope.confirmingDelete(
      auth: AuthRepository = signedIn(),
      medications: MedicationRepository = repository,
  ): PrescriptionDetailViewModel {
    repository.seed("alice", chemo)
    return viewModel(auth = auth, medications = medications).also {
      advanceUntilIdle()
      it.requestDelete()
    }
  }

  @Test
  fun beforeTheFirstRead_isLoading_andNeitherNotFoundNorError() {
    repository.seed("alice", chemo)

    val state = viewModel().uiState.value

    assertTrue(state.isLoading)
    assertFalse(state.hasError)
    assertFalse(state.isNotFound)
  }

  @Test
  fun existingPrescription_isShown() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()

    advanceUntilIdle()

    val state = vm.uiState.value
    assertEquals(chemo, state.prescription)
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertFalse(state.isNotFound)
  }

  @Test
  fun missingPrescription_isNotFound() = runTest {
    val vm = viewModel("missing")

    advanceUntilIdle()

    assertNull(vm.uiState.value.prescription)
    assertTrue(vm.uiState.value.isNotFound)
  }

  @Test
  fun anotherUsersPrescription_isNotFound() = runTest {
    repository.seed("bob", chemo)
    val vm = viewModel()

    advanceUntilIdle()

    assertTrue(vm.uiState.value.isNotFound)
  }

  @Test
  fun changeSavedLater_isShownWithoutReloading() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    // E.g. corrected in the edit form, which returns to this screen
    val corrected = chemo.copy(prescribedBy = "Dr. Dubois")
    repository.updatePrescription("alice", corrected)
    advanceUntilIdle()

    assertEquals("Dr. Dubois", vm.uiState.value.prescription?.prescribedBy)
  }

  @Test
  fun deletedElsewhere_becomesNotFound() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    repository.deletePrescription("alice", "chemo")
    advanceUntilIdle()

    assertTrue(vm.uiState.value.isNotFound)
  }

  @Test
  fun readError_showsTheError_andRetryShowsThePrescription() = runTest {
    repository.seed("alice", chemo)
    repository.readError = IllegalStateException("no cache")
    val vm = viewModel()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isNotFound)

    repository.readError = null
    vm.load()
    assertTrue(vm.uiState.value.isLoading)
    advanceUntilIdle()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(chemo, vm.uiState.value.prescription)
  }

  @Test
  fun readStoppedWhileShown_showsTheError() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    repository.failRunningReads(IllegalStateException("access lost"))
    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isNotFound)
  }

  @Test
  fun retry_readsOnlyOnce() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    vm.load()
    vm.load()
    advanceUntilIdle()

    assertEquals(1, repository.prescriptionObservers)
  }

  @Test
  fun signedOut_showsAnError() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel(auth = FakeAuthRepository())

    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isLoading)
  }

  @Test
  fun unreadableUser_showsAnError() = runTest {
    val throwing =
        object : AuthRepository by FakeAuthRepository() {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase not initialized")

          override fun observeCurrentUser(): Flow<AuthUser?> = throw UnsupportedOperationException()
        }

    val vm = viewModel(auth = throwing)
    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasError)
  }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() {
    val viewModel = PrescriptionDetailViewModel("chemo")
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.uiState.value.hasError)
  }

  // ----- Delete (US-12) -----

  @Test
  fun canEditAndDelete_onlyAShownPrescription() {
    val shown = PrescriptionDetailUiState(prescription = chemo, isLoading = false)

    assertTrue(shown.canDelete)
    assertTrue(shown.canEdit)
    listOf(
            PrescriptionDetailUiState(),
            shown.copy(prescription = null),
            shown.copy(hasError = true),
            shown.copy(delete = DeletePhase.DELETING),
            shown.copy(delete = DeletePhase.DELETED),
        )
        .forEach {
          assertFalse("Delete in $it", it.canDelete)
          assertFalse("Edit in $it", it.canEdit)
        }
  }

  @Test
  fun requestDelete_asksForConfirmation_andDeletesNothing() = runTest {
    val vm = confirmingDelete()
    advanceUntilIdle()

    assertTrue(vm.uiState.value.isDeleteDialogShown)
    assertEquals(listOf(chemo), repository.prescriptionsOf("alice"))
  }

  @Test
  fun cancelDelete_closesTheConfirmation_andKeepsThePrescription() = runTest {
    val vm = confirmingDelete()

    vm.cancelDelete()
    advanceUntilIdle()

    assertFalse(vm.uiState.value.isDeleteDialogShown)
    assertEquals(chemo, vm.uiState.value.prescription)
    assertEquals(listOf(chemo), repository.prescriptionsOf("alice"))
  }

  @Test
  fun confirmDelete_deletesThePrescription_andTheScreenIsDone() = runTest {
    val vm = confirmingDelete()

    vm.confirmDelete()
    advanceUntilIdle()

    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
    // Not shown as "no longer exists" while the screen closes
    assertFalse(vm.uiState.value.isNotFound)
    assertEquals(emptyList<Prescription>(), repository.prescriptionsOf("alice"))
  }

  @Test
  fun confirmDelete_rejectedLaterByTheServer_bringsThePrescriptionBackAndTellsTheUser() = runTest {
    val rejectedWrites = RejectedWrites()
    val medications = FakeMedicationRepository(rejectedWrites = rejectedWrites)
    medications.seed("alice", chemo)
    medications.serverRejectsWrites = true
    val vm = viewModel(medications = medications)
    advanceUntilIdle()
    vm.requestDelete()

    vm.confirmDelete()
    advanceUntilIdle()

    // Done without waiting for the server
    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
    assertEquals(emptyList<Prescription>(), medications.prescriptionsOf("alice"))
    assertEquals(0, rejectedWrites.unseen.value)

    medications.rejectPendingWrites()

    assertEquals(listOf(chemo), medications.prescriptionsOf("alice"))
    assertEquals(1, rejectedWrites.unseen.value)
  }

  @Test
  fun confirmDelete_withoutConfirmation_deletesNothing() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    vm.confirmDelete()
    advanceUntilIdle()

    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
    assertEquals(listOf(chemo), repository.prescriptionsOf("alice"))
  }

  @Test
  fun accessLostWhileConfirming_closesTheConfirmation_forGood() = runTest {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)

    // The access briefly goes away, then comes back with the prescription
    auth.signOut()
    advanceUntilIdle()
    auth.signInWithGoogle("token")
    advanceUntilIdle()

    assertEquals(chemo, vm.uiState.value.prescription)
    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
  }

  @Test
  fun requestDelete_isIgnoredWithoutAPrescription() = runTest {
    val vm = viewModel("missing")
    advanceUntilIdle()

    vm.requestDelete()

    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
  }

  @Test
  fun deleteError_keepsThePrescriptionAndTheDialog_andRetryDeletes() = runTest {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("refused")

    vm.confirmDelete()
    advanceUntilIdle()

    assertEquals(DeletePhase.FAILED, vm.uiState.value.delete)
    assertTrue(vm.uiState.value.isDeleteDialogShown)
    assertEquals(chemo, vm.uiState.value.prescription)

    repository.writeError = null
    vm.confirmDelete()
    advanceUntilIdle()

    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
  }

  @Test
  fun deleteError_isClearedWhenTheDialogIsClosed() = runTest {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("refused")
    vm.confirmDelete()
    advanceUntilIdle()

    vm.cancelDelete()
    vm.requestDelete()

    assertEquals(DeletePhase.CONFIRMING, vm.uiState.value.delete)
  }

  @Test
  fun signedOutBeforeConfirming_showsTheErrorAndDeletesNothing() = runTest {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)
    auth.signOut()

    vm.confirmDelete()
    advanceUntilIdle()

    // Signing out shows the error, closes the confirmation and deletes nothing
    assertTrue(vm.uiState.value.hasError)
    assertEquals(DeletePhase.IDLE, vm.uiState.value.delete)
    assertEquals(listOf(chemo), repository.prescriptionsOf("alice"))
  }

  @Test
  fun whileDeleting_theDialogCantBeClosedOrConfirmedAgain() = runTest {
    val deleted = CompletableDeferred<Unit>()
    var deletes = 0
    val slow =
        object : MedicationRepository by repository {
          override suspend fun deletePrescription(uid: String, id: String) {
            deletes++
            deleted.await()
            repository.deletePrescription(uid, id)
          }
        }
    val vm = confirmingDelete(medications = slow)

    vm.confirmDelete()
    advanceUntilIdle()
    assertEquals(DeletePhase.DELETING, vm.uiState.value.delete)
    assertTrue(vm.uiState.value.isDeleteDialogShown)
    vm.cancelDelete()
    vm.confirmDelete()
    advanceUntilIdle()
    assertEquals(DeletePhase.DELETING, vm.uiState.value.delete)
    assertEquals(1, deletes)

    deleted.complete(Unit)
    advanceUntilIdle()

    assertEquals(DeletePhase.DELETED, vm.uiState.value.delete)
  }

  @Test
  fun deletedElsewhereWhileConfirming_closesTheConfirmation() = runTest {
    val vm = confirmingDelete()

    repository.deletePrescription("alice", "chemo")
    advanceUntilIdle()

    assertFalse(vm.uiState.value.isDeleteDialogShown)
    assertTrue(vm.uiState.value.isNotFound)
  }

  @Test
  fun readErrorWhileConfirming_closesTheConfirmation() = runTest {
    val vm = confirmingDelete()

    repository.failRunningReads(IllegalStateException("access lost"))
    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isDeleteDialogShown)
  }

  // ----- Caregiver mode (US-20) -----

  private fun caregiverViewModel(vararg shared: CarePermission) =
      PrescriptionDetailViewModel(
          "chemo",
          signedIn("caregiver"),
          repository,
          patientUid = "patient",
          resolveSection = followedPatientAccess(*shared),
      )

  @Test
  fun caregiver_prescriptionsShared_showsThePatients_readOnly() {
    repository.seed("patient", chemo)

    val viewModel = caregiverViewModel(CarePermission.PRESCRIPTIONS)
    dispatcher.scheduler.advanceUntilIdle()

    assertEquals(chemo, viewModel.uiState.value.prescription)
    assertFalse(viewModel.uiState.value.canEdit)
    assertFalse(viewModel.uiState.value.canDelete)
  }

  @Test
  fun caregiver_prescriptionsNotShared_isNotShared_notNotFound() {
    repository.seed("patient", chemo)

    val viewModel = caregiverViewModel(CarePermission.SYMPTOMS)
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.isNotShared)
    assertFalse(viewModel.uiState.value.isNotFound)
    assertFalse(viewModel.uiState.value.hasError)
  }

  /** A caregiver's view of "patient"'s prescription, while [patient] changes the access. */
  private fun followedViewModel(patient: FollowedPatient) =
      PrescriptionDetailViewModel(
          "chemo",
          patient.auth,
          repository,
          patientUid = "patient",
          resolveSection = patient.sectionAccess,
      )

  @Test
  fun caregiver_accessUnknownAgain_hidesThePrescription() {
    val patient = FollowedPatient(CarePermission.PRESCRIPTIONS)
    repository.seed("patient", chemo)
    val viewModel = followedViewModel(patient)
    dispatcher.scheduler.advanceUntilIdle()
    assertEquals(chemo, viewModel.uiState.value.prescription)

    // e.g. the membership now only comes from the cache, and the server hasn't confirmed it
    patient.isUnconfirmed()
    dispatcher.scheduler.advanceUntilIdle()

    assertNull(viewModel.uiState.value.prescription)
    assertTrue(viewModel.uiState.value.isLoading)
  }

  @Test
  fun caregiver_readRefusedOnceShared_isAnErrorOnly_notNotShared() {
    val patient = FollowedPatient(CarePermission.SYMPTOMS)
    repository.seed("patient", chemo)
    val viewModel = followedViewModel(patient)
    dispatcher.scheduler.advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isNotShared)

    repository.readError = IllegalStateException("Permission denied")
    patient.shares(CarePermission.PRESCRIPTIONS)
    dispatcher.scheduler.advanceUntilIdle()

    assertTrue(viewModel.uiState.value.hasError)
    assertFalse(viewModel.uiState.value.isNotShared)
  }

  @Test
  fun addedByACaregiver_followsARename_andKeepsTheStoredNameOnceTheyLeave() = runTest {
    repository.seed(
        "alice",
        chemo.copy(
            addedBy = "caregiver",
            addedByName = "Sam",
            confirmation = PrescriptionConfirmation.PENDING,
        ),
    )
    val circle = FakeCareCircleRepository()
    fun named(firstName: String) =
        circle.setMembers(
            "alice",
            listOf(CareCircleMember(uid = "caregiver", firstName = firstName)),
        )
    named("Samuel")
    val viewModel =
        PrescriptionDetailViewModel("chemo", signedIn(), repository, careCircleRepository = circle)
    advanceUntilIdle()

    // Renamed while the detail is open, then removed from the circle
    val steps =
        listOf({ named("Sammy") } to "Sammy", { circle.setMembers("alice", emptyList()) } to "Sam")
    assertEquals("Samuel", viewModel.uiState.value.prescription?.addedByName)
    for ((change, name) in steps) {
      change()
      advanceUntilIdle()
      assertEquals(name, viewModel.uiState.value.prescription?.addedByName)
    }
    assertEquals(
        PrescriptionConfirmation.PENDING,
        viewModel.uiState.value.prescription?.confirmation,
    )
  }
}
