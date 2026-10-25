// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.prescription

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.medication.FakeMedicationRepository
import com.github.se.oncompanion.model.medication.Medication
import com.github.se.oncompanion.model.medication.Prescription
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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

  private fun viewModel(id: String = "chemo", auth: AuthRepository = signedIn()) =
      PrescriptionDetailViewModel(id, auth, repository)

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
}
