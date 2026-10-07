package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.Screen
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
class CareCircleMemberViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val ownerUid = "uid-owner"

  private val marc =
      CareCircleMember(
          uid = "marc",
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
          email = "marc.dubois@email.com",
      )
  private val sophie = CareCircleMember(uid = "sophie", firstName = "Sophie")

  private lateinit var repository: FakeCareCircleRepository
  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() = runTest {
    Dispatchers.setMain(dispatcher)
    repository = FakeCareCircleRepository()
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = ownerUid) })
    auth.signInWithGoogle("token")
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** The ViewModel as navigation creates it, with the member's uid as route argument. */
  private fun createViewModel(
      memberUid: String? = marc.uid,
      authRepository: AuthRepository = auth,
  ): CareCircleMemberViewModel {
    val args =
        if (memberUid == null) emptyMap() else mapOf(Screen.CARE_CIRCLE_MEMBER_ARG to memberUid)
    return CareCircleMemberViewModel(SavedStateHandle(args), repository, authRepository)
  }

  @Test
  fun initialState_isLoading() {
    val viewModel = createViewModel()
    assertEquals(CareCircleMemberUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun memberInTheCircle_isShown() = runTest {
    repository.setMembers(ownerUid, listOf(sophie, marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Member(marc), viewModel.uiState.value)
    assertEquals(listOf(ownerUid), repository.observedOwners)
  }

  @Test
  fun memberNotInTheCircle_isNotFound() = runTest {
    repository.setMembers(ownerUid, listOf(sophie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.NotFound, viewModel.uiState.value)
  }

  @Test
  fun memberOfAnotherUsersCircle_isNotFound() = runTest {
    repository.setMembers("someone-else", listOf(marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.NotFound, viewModel.uiState.value)
  }

  @Test
  fun changesToTheMember_updateTheState() = runTest {
    repository.setMembers(ownerUid, listOf(marc))
    val viewModel = createViewModel()
    advanceUntilIdle()

    val updated = marc.copy(permissions = CarePermission.entries.toSet())
    repository.setMembers(ownerUid, listOf(updated))
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Member(updated), viewModel.uiState.value)
  }

  @Test
  fun memberRemovedWhileOpen_isNotFound() = runTest {
    repository.setMembers(ownerUid, listOf(marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Member(marc), viewModel.uiState.value)

    repository.setMembers(ownerUid, emptyList())
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.NotFound, viewModel.uiState.value)
  }

  @Test
  fun readFailure_isError() = runTest {
    repository.observeError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun nobodySignedIn_isError_withoutReadingAnyCircle() = runTest {
    auth.signOut()
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Error, viewModel.uiState.value)
    assertEquals(emptyList<String>(), repository.observedOwners)
  }

  @Test
  fun missingOrBlankMemberArgument_isError_withoutReadingAnyCircle() = runTest {
    listOf(null, " ").forEach { memberUid ->
      val viewModel = createViewModel(memberUid = memberUid)
      advanceUntilIdle()
      assertEquals(CareCircleMemberUiState.Error, viewModel.uiState.value)
    }
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
    val viewModel = createViewModel(authRepository = failingAuth)
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun retry_afterError_showsLoadingThenTheMember() = runTest {
    repository.observeError = IllegalStateException("boom")
    repository.setMembers(ownerUid, listOf(marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Error, viewModel.uiState.value)

    repository.observeError = null
    viewModel.retry()
    assertEquals(CareCircleMemberUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Member(marc), viewModel.uiState.value)
    assertEquals(listOf(ownerUid, ownerUid), repository.observedOwners)
  }

  @Test
  fun retry_stopsThePreviousObservation() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    viewModel.retry()
    advanceUntilIdle()

    // Only the new observation updates the state: no stale "NotFound" from the first one
    repository.setMembers(ownerUid, listOf(marc))
    advanceUntilIdle()
    assertEquals(CareCircleMemberUiState.Member(marc), viewModel.uiState.value)
  }
}
