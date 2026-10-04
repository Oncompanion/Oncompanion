package com.github.se.oncompanion.ui.closecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.closecircle.CarePermission
import com.github.se.oncompanion.model.closecircle.CloseCircleMember
import com.github.se.oncompanion.model.closecircle.FakeCloseCircleRepository
import com.github.se.oncompanion.model.closecircle.Relationship
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloseCircleViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val ownerUid = "uid-owner"

  private val sophie =
      CloseCircleMember(
          uid = "sophie",
          firstName = "Sophie",
          familyName = "Dubois",
          relationship = Relationship.WIFE,
          permissions = CarePermission.entries.toSet(),
      )
  private val marc =
      CloseCircleMember(
          uid = "marc",
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
      )

  private lateinit var repository: FakeCloseCircleRepository
  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() = runTest {
    Dispatchers.setMain(dispatcher)
    repository = FakeCloseCircleRepository()
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = ownerUid) })
    auth.signInWithGoogle("token")
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(authRepository: AuthRepository = auth) =
      CloseCircleViewModel(repository, authRepository)

  @Test
  fun initialState_isLoading() {
    val viewModel = createViewModel()
    assertEquals(CloseCircleUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun emptyCircle_isEmpty() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Empty, viewModel.uiState.value)
    assertEquals(listOf(ownerUid), repository.observedOwners)
  }

  @Test
  fun circleWithMembers_listsThemSortedByName() = runTest {
    repository.setMembers(ownerUid, listOf(sophie, marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Members(listOf(marc, sophie)), viewModel.uiState.value)
  }

  @Test
  fun onlyTheSignedInUsersCircleIsShown() = runTest {
    repository.setMembers("someone-else", listOf(sophie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun changesToTheCircle_updateTheState() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Empty, viewModel.uiState.value)

    repository.setMembers(ownerUid, listOf(sophie))
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Members(listOf(sophie)), viewModel.uiState.value)

    repository.setMembers(ownerUid, emptyList())
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun readFailure_isError() = runTest {
    repository.observeError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun nobodySignedIn_isError_withoutReadingAnyCircle() = runTest {
    auth.signOut()
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Error, viewModel.uiState.value)
    assertEquals(emptyList<String>(), repository.observedOwners)
  }

  @Test
  fun authFailure_isError() = runTest {
    val failingAuth =
        object : AuthRepository {
          override val currentUser: AuthUser?
            get() = throw IllegalStateException("Firebase not initialized")

          override fun observeCurrentUser(): Flow<AuthUser?> = throw UnsupportedOperationException()

          override suspend fun signInWithGoogle(idToken: String): AuthUser =
              throw UnsupportedOperationException()

          override fun signOut() = Unit
        }
    val viewModel = createViewModel(failingAuth)
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun retry_afterError_showsLoadingThenTheCircle() = runTest {
    repository.observeError = IllegalStateException("boom")
    repository.setMembers(ownerUid, listOf(sophie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Error, viewModel.uiState.value)

    repository.observeError = null
    viewModel.retry()
    assertEquals(CloseCircleUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Members(listOf(sophie)), viewModel.uiState.value)
    assertEquals(listOf(ownerUid, ownerUid), repository.observedOwners)
  }

  @Test
  fun retry_stopsThePreviousObservation() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    viewModel.retry()
    advanceUntilIdle()

    // Only the new observation updates the state: no stale "Empty" from the first one
    repository.setMembers(ownerUid, listOf(sophie))
    advanceUntilIdle()
    assertEquals(CloseCircleUiState.Members(listOf(sophie)), viewModel.uiState.value)
  }
}
