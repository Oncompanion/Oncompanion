package com.github.se.oncompanion.ui.profile

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class MutableAuthRepository(initialUser: AuthUser?) : AuthRepository {
  val users = MutableStateFlow(initialUser)

  override val currentUser: AuthUser?
    get() = users.value

  override fun observeCurrentUser(): Flow<AuthUser?> = users

  override suspend fun signInWithGoogle(idToken: String): AuthUser = error("unused")

  override fun signOut() {
    users.value = null
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val memberSince = Instant.parse("2026-09-04T12:00:00Z")
  private val user = AuthUser(uid = "uid-1", email = "alex@example.com")

  private lateinit var auth: MutableAuthRepository
  private lateinit var profiles: FakeUserProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = MutableAuthRepository(user)
    profiles = FakeUserProfileRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = ProfileViewModel(auth, profiles)

  @Test
  fun initialState_isLoadingUntilAuthenticationAndProfileAreObserved() = runTest {
    profiles.seed(
        UserProfile(
            uid = user.uid,
            role = Role.PATIENT,
            firstName = "Alex",
            createdAt = memberSince,
        )
    )
    val vm = viewModel()

    assertEquals(ProfileUiState.Loading, vm.uiState.value)
    advanceUntilIdle()

    assertEquals(
        ProfileUiState.Loaded(
            ProfileDetails(
                firstName = "Alex",
                familyName = null,
                cancerType = null,
                email = user.email,
                memberSince = memberSince,
            )
        ),
        vm.uiState.value,
    )
  }

  @Test
  fun profileFields_andEmailComeFromTheAuthenticatedUserAndItsProfile() = runTest {
    profiles.seed(
        UserProfile(
            uid = user.uid,
            role = Role.CAREGIVER,
            firstName = "Alex",
            familyName = "Moreau",
            cancerType = "Breast cancer",
            createdAt = memberSince,
        )
    )
    profiles.seed(
        UserProfile(
            uid = "another-user",
            role = Role.PATIENT,
            firstName = "Other",
        )
    )
    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(
        ProfileUiState.Loaded(
            ProfileDetails(
                firstName = "Alex",
                familyName = "Moreau",
                cancerType = "Breast cancer",
                email = "alex@example.com",
                memberSince = memberSince,
            )
        ),
        vm.uiState.value,
    )
  }

  @Test
  fun profileChanges_areObserved() = runTest {
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    val vm = viewModel()
    advanceUntilIdle()

    profiles.updateProfile(
        UserProfile(
            uid = user.uid,
            role = Role.PATIENT,
            firstName = "Alexandra",
            familyName = "Moreau",
            cancerType = "Lymphoma",
        )
    )
    advanceUntilIdle()

    assertEquals(
        ProfileUiState.Loaded(
            ProfileDetails(
                firstName = "Alexandra",
                familyName = "Moreau",
                cancerType = "Lymphoma",
                email = user.email,
                memberSince = null,
            )
        ),
        vm.uiState.value,
    )
  }

  @Test
  fun authenticationChanges_switchTheObservedProfile() = runTest {
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    profiles.seed(UserProfile(uid = "uid-2", role = Role.PATIENT, firstName = "Sam"))
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals("Alex", (vm.uiState.value as ProfileUiState.Loaded).details.firstName)

    auth.users.value = AuthUser(uid = "uid-2", email = "sam@example.com")
    advanceUntilIdle()

    assertEquals(
        ProfileUiState.Loaded(ProfileDetails("Sam", null, null, "sam@example.com", null)),
        vm.uiState.value,
    )
  }

  @Test
  fun noSignedInUser_showsSignedOut_andDoesNotCreateAProfileRepository() = runTest {
    auth.users.value = null
    val vm = ProfileViewModel(auth, profiles)
    advanceUntilIdle()

    assertEquals(ProfileUiState.SignedOut, vm.uiState.value)
  }

  @Test
  fun missingProfile_hasItsOwnState() = runTest {
    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(ProfileUiState.MissingProfile, vm.uiState.value)
  }

  @Test
  fun missingEmailAndMemberSinceRemainOptional() = runTest {
    auth.users.value = AuthUser(uid = user.uid)
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    val vm = viewModel()
    advanceUntilIdle()

    assertEquals(
        ProfileUiState.Loaded(ProfileDetails("Alex", null, null, null, null)),
        vm.uiState.value,
    )
  }

  @Test
  fun profileRepositoryError_showsError_andRetryRecovers() = runTest {
    profiles.getProfileError = IllegalStateException("read failed")
    val vm = viewModel()
    advanceUntilIdle()
    assertEquals(ProfileUiState.Error, vm.uiState.value)

    profiles.getProfileError = null
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    vm.retry()
    advanceUntilIdle()

    assertEquals("Alex", (vm.uiState.value as ProfileUiState.Loaded).details.firstName)
  }

  @Test
  fun authenticationObservationError_showsError() = runTest {
    val brokenAuth =
        object : AuthRepository by auth {
          override fun observeCurrentUser(): Flow<AuthUser?> = flow {
            throw IllegalStateException("auth unavailable")
          }
        }
    val vm = ProfileViewModel(brokenAuth, profiles)
    advanceUntilIdle()

    assertTrue(vm.uiState.value is ProfileUiState.Error)
  }
}
