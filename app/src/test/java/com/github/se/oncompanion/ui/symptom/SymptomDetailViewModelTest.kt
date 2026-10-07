package com.github.se.oncompanion.ui.symptom

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.symptom.FakeSymptomRepository
import com.github.se.oncompanion.model.symptom.SymptomEntry
import com.github.se.oncompanion.model.symptom.SymptomType
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric provides the main dispatcher used by viewModelScope
@RunWith(AndroidJUnit4::class)
class SymptomDetailViewModelTest {

  private val repository = FakeSymptomRepository()

  private val fatigue =
      SymptomEntry(
          id = "s1",
          type = SymptomType.FATIGUE,
          intensity = 7,
          occurredAt = Instant.parse("2026-10-05T08:00:00Z"),
          notes = "After the walk",
      )

  private fun signedIn(uid: String = "alice") =
      FakeAuthRepository(onSignIn = { AuthUser(uid = uid) }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  private fun viewModel(id: String = "s1", auth: AuthRepository = signedIn()) =
      SymptomDetailViewModel(id, auth, repository)

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
}
