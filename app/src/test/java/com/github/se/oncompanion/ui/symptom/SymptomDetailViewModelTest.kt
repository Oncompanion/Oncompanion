// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.symptom

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.DeleteState
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class SymptomDetailViewModelTest {

  private val repository = FakeSymptomRepository()

  private val fatigue =
      SymptomEntry(
          id = "s1",
          types = listOf(SymptomType.FATIGUE),
          intensity = 7,
          occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
          notes = "After the walk",
      )

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(
      id: String = "s1",
      auth: AuthRepository = signedIn(),
      symptoms: SymptomRepository = repository,
  ) = SymptomDetailViewModel(id, auth, symptoms)

  /** A ViewModel showing [fatigue], with the deletion confirmation open. */
  private fun confirmingDelete(
      auth: AuthRepository = signedIn(),
      symptoms: SymptomRepository = repository,
  ): SymptomDetailViewModel {
    repository.seed("alice", fatigue)
    return viewModel(auth = auth, symptoms = symptoms).apply { requestDelete() }
  }

  @Test
  fun existingEntry_isLoaded() {
    repository.seed("alice", fatigue)

    val state = viewModel().uiState.value

    assertEquals(fatigue, state.entry)
    assertFalse(state.isLoading)
    assertFalse(state.hasError)
    assertFalse(state.isNotFound)
  }

  @Test
  fun missingEntry_isNotFound() {
    val state = viewModel("missing").uiState.value

    assertNull(state.entry)
    assertTrue(state.isNotFound)
  }

  @Test
  fun anotherUsersEntry_isNotFound() {
    repository.seed("bob", fatigue)

    assertTrue(viewModel().uiState.value.isNotFound)
  }

  @Test
  fun loading_isNeitherNotFoundNorError() {
    val loading = SymptomDetailUiState()

    assertTrue(loading.isLoading)
    assertFalse(loading.isNotFound)
    assertFalse(loading.copy(isLoading = false, hasError = true).isNotFound)
  }

  @Test
  fun canDelete_onlyAShownEntry() {
    val shown = SymptomDetailUiState(entry = fatigue, isLoading = false)

    assertTrue(shown.canDelete)
    assertFalse(SymptomDetailUiState().canDelete)
    assertFalse(shown.copy(entry = null).canDelete)
    assertFalse(shown.copy(hasError = true).canDelete)
    assertFalse(shown.copy(delete = DeleteState(isDeleting = true)).canDelete)
    assertFalse(shown.copy(delete = DeleteState(isDeleted = true)).canDelete)
  }

  @Test
  fun loadError_showsTheError_andRetryLoads() {
    repository.seed("alice", fatigue)
    repository.observeError = IllegalStateException("no cache")
    val vm = viewModel()
    assertTrue(vm.uiState.value.hasError)
    assertFalse(vm.uiState.value.isNotFound)

    repository.observeError = null
    vm.load()

    assertFalse(vm.uiState.value.hasError)
    assertEquals(fatigue, vm.uiState.value.entry)
  }

  @Test
  fun signedOut_showsAnError() {
    repository.seed("alice", fatigue)

    val state = viewModel(auth = FakeAuthRepository()).uiState.value

    assertTrue(state.hasError)
    assertFalse(state.isLoading)
  }

  @Test
  fun unreadableUser_showsAnError() {
    val throwing =
        object : AuthRepository by FakeAuthRepository() {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase not initialized")

          override fun observeCurrentUser(): Flow<AuthUser?> = throw UnsupportedOperationException()
        }

    assertTrue(viewModel(auth = throwing).uiState.value.hasError)
  }

  @Test
  fun defaultRepositories_canBeCreatedWithoutFirebase() {
    assertTrue(SymptomDetailViewModel("s1").uiState.value.hasError)
  }

  // ----- Delete -----

  @Test
  fun requestDelete_asksForConfirmation_andDeletesNothing() {
    val vm = confirmingDelete()

    assertTrue(vm.uiState.value.delete.isConfirming)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun cancelDelete_closesTheConfirmation_andKeepsTheEntry() {
    val vm = confirmingDelete()

    vm.cancelDelete()

    assertFalse(vm.uiState.value.delete.isConfirming)
    assertEquals(fatigue, vm.uiState.value.entry)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun confirmDelete_deletesTheEntry_andTheScreenIsDone() {
    val vm = confirmingDelete()

    vm.confirmDelete()

    val state = vm.uiState.value
    assertTrue(repository.symptoms("alice").isEmpty())
    assertTrue(state.delete.isDeleted)
    assertFalse(state.delete.isConfirming)
    assertFalse(state.delete.isDeleting)
    // Still shows the entry until the screen closes, not "no longer exists"
    assertEquals(fatigue, state.entry)
    assertFalse(state.isNotFound)
    assertFalse(state.canDelete)
  }

  @Test
  fun confirmDelete_withoutConfirmation_deletesNothing() {
    repository.seed("alice", fatigue)
    val vm = viewModel()

    vm.confirmDelete()

    assertFalse(vm.uiState.value.delete.isDeleted)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun requestDelete_isIgnoredWithoutAnEntry() {
    val notFound = viewModel("missing")
    notFound.requestDelete()
    assertFalse(notFound.uiState.value.delete.isConfirming)

    repository.observeError = IllegalStateException("no cache")
    val error = viewModel()
    error.requestDelete()
    assertFalse(error.uiState.value.delete.isConfirming)
  }

  @Test
  fun deleteError_keepsTheEntryAndTheDialog_andRetryDeletes() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")

    vm.confirmDelete()

    var state = vm.uiState.value
    assertTrue(state.delete.hasError)
    assertTrue(state.delete.isConfirming)
    // No loading state in between: the dialog stays open with the error
    assertFalse(state.isLoading)
    assertTrue(state.isDeleteDialogShown)
    assertFalse(state.delete.isDeleted)
    assertEquals(fatigue, state.entry)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))

    repository.writeError = null
    vm.confirmDelete()

    state = vm.uiState.value
    assertTrue(state.delete.isDeleted)
    assertFalse(state.delete.hasError)
    assertTrue(repository.symptoms("alice").isEmpty())
  }

  @Test
  fun deleteError_isClearedWhenTheDialogIsClosed() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")
    vm.confirmDelete()

    vm.cancelDelete()
    vm.requestDelete()

    assertTrue(vm.uiState.value.delete.isConfirming)
    assertFalse(vm.uiState.value.delete.hasError)
  }

  @Test
  fun signedOutBeforeConfirming_showsTheErrorAndDeletesNothing() {
    val auth = signedIn()
    val vm = confirmingDelete(auth = auth)
    auth.signOut()

    vm.confirmDelete()

    assertTrue(vm.uiState.value.delete.hasError)
    assertFalse(vm.uiState.value.delete.isDeleted)
    assertEquals(listOf(fatigue), repository.symptoms("alice"))
  }

  @Test
  fun whileDeleting_theDialogCantBeClosedOrConfirmedAgain() {
    val deleted = CompletableDeferred<Unit>()
    var deletes = 0
    val slow =
        object : SymptomRepository by repository {
          override suspend fun deleteSymptom(uid: String, id: String) {
            deletes++
            deleted.await()
            repository.deleteSymptom(uid, id)
          }
        }
    val vm = confirmingDelete(symptoms = slow)

    vm.confirmDelete()
    assertTrue(vm.uiState.value.delete.isDeleting)
    assertFalse(vm.uiState.value.canDelete)
    vm.cancelDelete()
    vm.confirmDelete()
    assertTrue(vm.uiState.value.delete.isConfirming)
    assertEquals(1, deletes)

    deleted.complete(Unit)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(vm.uiState.value.delete.isDeleted)
    assertFalse(vm.uiState.value.delete.isDeleting)
  }

  @Test
  fun deleteDialog_isShownOnlyWhileDeleteCanDoSomething() {
    val confirming =
        SymptomDetailUiState(
            entry = fatigue,
            isLoading = false,
            delete = DeleteState(isConfirming = true),
        )

    assertTrue(confirming.isDeleteDialogShown)
    assertTrue(
        confirming.copy(delete = confirming.delete.copy(isDeleting = true)).isDeleteDialogShown
    )
    assertFalse(
        confirming.copy(delete = confirming.delete.copy(isConfirming = false)).isDeleteDialogShown
    )
    assertFalse(confirming.copy(entry = null).isDeleteDialogShown)
    assertFalse(confirming.copy(isLoading = true).isDeleteDialogShown)
    assertFalse(confirming.copy(hasError = true).isDeleteDialogShown)
  }

  @Test
  fun entryDeletedElsewhereWhileConfirming_closesTheConfirmation() {
    val vm = confirmingDelete()

    // Deleted on another device
    runBlocking { repository.deleteSymptom("alice", "s1") }

    var state = vm.uiState.value
    assertTrue(state.isNotFound)
    assertFalse(state.delete.isConfirming)
    assertFalse(state.isDeleteDialogShown)

    // Coming back (e.g. the deletion was rolled back) doesn't reopen the dialog
    repository.seed("alice", fatigue)
    state = vm.uiState.value
    assertEquals(fatigue, state.entry)
    assertFalse(state.isDeleteDialogShown)
  }

  @Test
  fun loadErrorAfterAFailedDelete_closesTheConfirmation() {
    val vm = confirmingDelete()
    repository.writeError = IllegalStateException("rejected")
    repository.observeError = IllegalStateException("no cache")

    vm.confirmDelete()

    val state = vm.uiState.value
    assertTrue(state.hasError)
    assertFalse(state.delete.isConfirming)
    assertFalse(state.delete.hasError)
    assertFalse(state.isDeleteDialogShown)
  }
}
