package com.github.se.oncompanion.ui.profile

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Sign-out obeys confirmation, destroys profile state, and never waits for Firebase networking. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSignOutViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var auth: FakeAuthRepository
  private lateinit var profiles: FakeUserProfileRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository()
    profiles = FakeUserProfileRepository()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private suspend fun model(): ProfileViewModel {
    val user = auth.signInWithGoogle("one")
    profiles.seed(UserProfile(user.uid, Role.PATIENT, "Alex", cancerType = "Private"))
    return ProfileViewModel(auth, profiles)
  }

  @Test
  fun requestAndCancelKeepSessionAndLatestProfile() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.requestSignOut()
    assertTrue(vm.uiState.value is ProfileUiState.ConfirmingSignOut)
    assertEquals(0, auth.signOutCalls)
    profiles.seed(UserProfile(auth.currentUser!!.uid, Role.PATIENT, "Updated"))
    advanceUntilIdle()
    vm.cancelSignOut()
    assertEquals("Updated", (vm.uiState.value as ProfileUiState.Loaded).details.firstName)
    assertNotNull(auth.currentUser)
    assertEquals(0, auth.signOutCalls)
  }

  @Test
  fun confirmationIsRequiredAndCannotBeOpenedWhenSignedOut() = runTest {
    val vm = ProfileViewModel(auth, profiles)
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut { fail("Cleanup must not run") }
    assertEquals(ProfileUiState.SignedOut, vm.uiState.value)
    assertEquals(0, auth.signOutCalls)
    val signedIn = model()
    advanceUntilIdle()
    signedIn.confirmSignOut { fail("Confirmation is required") }
    signedIn.cancelSignOut()
    assertNotNull(auth.currentUser)
    assertEquals(0, auth.signOutCalls)
  }

  @Test
  fun confirmSignsOutOnceAndDropsCachedDetailsBeforeCleanupFinishes() = runTest {
    val vm = model()
    advanceUntilIdle()
    val uid = auth.currentUser!!.uid
    val cleanup = CompletableDeferred<Unit>()
    var clears = 0
    vm.requestSignOut()
    vm.confirmSignOut {
      clears++
      cleanup.await()
    }
    assertNull(auth.currentUser)
    assertEquals(ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true), vm.uiState.value)
    vm.confirmSignOut { fail("Duplicate cleanup") }
    vm.cancelSignOut()
    vm.requestSignOut()
    vm.retry()
    profiles.seed(UserProfile(uid, Role.PATIENT, "Late cached profile"))
    advanceUntilIdle()
    assertEquals(ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true), vm.uiState.value)
    assertEquals(1, auth.signOutCalls)
    assertEquals(1, clears)
    cleanup.complete(Unit)
    advanceUntilIdle()
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
    vm.confirmSignOut { fail("Completed twice") }
    vm.requestSignOut()
    vm.retry()
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
    assertEquals(1, auth.signOutCalls)
  }

  @Test
  fun firebaseFailureRetainsSessionAndAllowsRetry() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    auth.signOutError = IllegalStateException("Immediate failure")
    vm.confirmSignOut { fail("Must not clear credentials on Firebase failure") }
    val failure = vm.uiState.value as ProfileUiState.ConfirmingSignOut
    assertTrue(failure.failed)
    assertFalse(failure.isSigningOut)
    assertTrue(failure.profile is ProfileUiState.Loaded)
    assertNotNull(auth.currentUser)
    auth.signOutError = null
    vm.confirmSignOut {}
    advanceUntilIdle()
    assertNull(auth.currentUser)
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
  }

  @Test
  fun credentialFailureStillLeavesSignedOutUserAtCompletion() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut { error("Credential provider unavailable") }
    advanceUntilIdle()
    assertNull(auth.currentUser)
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
  }

  @Test
  fun cancelledCredentialCleanupDoesNotReportFirebaseFailure() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut { throw CancellationException("Screen removed") }
    advanceUntilIdle()
    assertNull(auth.currentUser)
    assertFalse((vm.uiState.value as ProfileUiState.ConfirmingSignOut).failed)
  }

  @Test
  fun newProfileViewModelAfterSignOutCannotReadPreviousCachedProfile() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut {}
    advanceUntilIdle()
    val reopened = ProfileViewModel(auth, profiles)
    advanceUntilIdle()
    assertEquals(ProfileUiState.SignedOut, reopened.uiState.value)
    assertTrue(profiles.profiles.isNotEmpty()) // Persistence is retained, but not exposed by UI.
    auth.onSignIn = { AuthUser("other") }
    profiles.seed(UserProfile("other", Role.PATIENT, "Sam"))
    auth.signInWithGoogle("two")
    advanceUntilIdle()
    assertEquals("Sam", (reopened.uiState.value as ProfileUiState.Loaded).details.firstName)
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
  }
}
