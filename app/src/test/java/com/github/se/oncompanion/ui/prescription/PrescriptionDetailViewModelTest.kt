// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.MedicationRepository
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
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
    assertTrue(PrescriptionDetailViewModel("chemo").uiState.value.hasError)
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
            shown.copy(isDeleting = true),
            shown.copy(isDeleted = true),
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

    assertTrue(vm.uiState.value.isDeleted)
    assertFalse(vm.uiState.value.isDeleting)
    // Not shown as "no longer exists" while the screen closes
    assertFalse(vm.uiState.value.isNotFound)
    assertEquals(emptyList<Prescription>(), repository.prescriptionsOf("alice"))
  }

  @Test
  fun confirmDelete_withoutConfirmation_deletesNothing() = runTest {
    repository.seed("alice", chemo)
    val vm = viewModel()
    advanceUntilIdle()

    vm.confirmDelete()
    advanceUntilIdle()

    assertFalse(vm.uiState.value.isDeleted)
    assertEquals(listOf(chemo), repository.prescriptionsOf("alice"))
  }

  @Test
  fun requestDelete_isIgnoredWithoutAPrescription() = runTest {
    val vm = viewModel("missing")
    advanceUntilIdle()

    vm.requestDelete()

    assertFalse(vm.uiState.value.isConfirmingDelete)
  }

  @Test
  fun deleteError_keepsThePrescriptionAndTheDialog_andRetryDeletes() = runTest {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("refused")

    vm.confirmDelete()
    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasDeleteError)
    assertTrue(vm.uiState.value.isDeleteDialogShown)
    assertFalse(vm.uiState.value.isDeleted)
    assertEquals(chemo, vm.uiState.value.prescription)

    repository.writeError = null
    vm.confirmDelete()
    advanceUntilIdle()

    assertTrue(vm.uiState.value.isDeleted)
    assertFalse(vm.uiState.value.hasDeleteError)
  }

  @Test
  fun deleteError_isClearedWhenTheDialogIsClosed() = runTest {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("refused")
    vm.confirmDelete()
    advanceUntilIdle()

    vm.cancelDelete()
    vm.requestDelete()

    assertFalse(vm.uiState.value.hasDeleteError)
  }

  @Test
  fun signedOutBeforeConfirming_showsTheErrorAndDeletesNothing() = runTest {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)
    auth.signOut()

    vm.confirmDelete()
    advanceUntilIdle()

    assertTrue(vm.uiState.value.hasDeleteError)
    assertFalse(vm.uiState.value.isDeleted)
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
    assertTrue(vm.uiState.value.isDeleting)
    assertTrue(vm.uiState.value.isDeleteDialogShown)
    vm.cancelDelete()
    vm.confirmDelete()
    advanceUntilIdle()
    assertTrue(vm.uiState.value.isConfirmingDelete)
    assertEquals(1, deletes)

    deleted.complete(Unit)
    advanceUntilIdle()

    assertTrue(vm.uiState.value.isDeleted)
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
}
