// Co-authored-by: ChatGPT <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.profile

import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
  private lateinit var signOut: SignOut
  private val local = FakeLocalSessionRepository()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository()
    profiles = FakeUserProfileRepository()
    signOut = SignOut(auth, local).apply { initialize(FakeCleanupJournal()) }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private suspend fun model(): ProfileViewModel {
    val user = auth.signInWithGoogle("one")
    profiles.seed(UserProfile(user.uid, Role.PATIENT, "Alex", cancerType = "Private"))
    return ProfileViewModel(auth, profiles, signOut)
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
    val vm = ProfileViewModel(auth, profiles, signOut)
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
    runCurrent()
    assertNull(auth.currentUser)
    assertEquals(ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true), vm.uiState.value)
    vm.confirmSignOut { fail("Duplicate cleanup") }
    vm.cancelSignOut()
    vm.requestSignOut()
    vm.retry()
    profiles.seed(UserProfile(uid, Role.PATIENT, "Late cached profile"))
    runCurrent()
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
  fun firebaseFailureLocksPrivateDetailsAndAllowsCleanupRetry() = runTest {
    val vm = model()
    advanceUntilIdle()
    auth.signOutError = IllegalStateException("Immediate failure")
    vm.requestSignOut()
    vm.confirmSignOut {}
    advanceUntilIdle()
    assertEquals(SignOutState.FAILED, signOut.state.value)
    assertEquals(ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true), vm.uiState.value)
    assertNotNull(auth.currentUser)
    auth.signOutError = null
    signOut.retry {}
    advanceUntilIdle()
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
  }

  @Test
  fun credentialFailureCannotReportSuccessfulCleanup() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut { error("Credential provider unavailable") }
    advanceUntilIdle()
    assertNull(auth.currentUser)
    assertEquals(SignOutState.FAILED, signOut.state.value)
    assertEquals(ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true), vm.uiState.value)
  }

  @Test
  fun unsynchronizedChangesNeedASecondConfirmationAndCancelKeepsProfile() = runTest {
    val vm = model()
    advanceUntilIdle()
    local.synchronizeAction = { error("unavailable") }
    vm.requestSignOut()
    vm.confirmSignOut {}
    advanceUntilIdle()
    val decision = vm.uiState.value as ProfileUiState.ConfirmingSignOut
    assertTrue(decision.pendingChanges)
    assertFalse(decision.isSigningOut)
    vm.cancelSignOut()
    assertTrue(vm.uiState.value is ProfileUiState.Loaded)
    assertNotNull(auth.currentUser)
    assertTrue(local.offlineChanges)
    vm.requestSignOut()
    vm.confirmSignOut {}
    advanceUntilIdle()
    vm.confirmSignOut {}
    advanceUntilIdle()
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
    assertNull(auth.currentUser)
  }

  @Test
  fun newProfileViewModelAfterSignOutCannotReadPreviousCachedProfile() = runTest {
    val vm = model()
    advanceUntilIdle()
    vm.requestSignOut()
    vm.confirmSignOut {}
    advanceUntilIdle()
    val reopened = ProfileViewModel(auth, profiles, signOut)
    advanceUntilIdle()
    assertEquals(ProfileUiState.SignedOut, reopened.uiState.value)
    assertTrue(
        profiles.profiles.isNotEmpty()
    ) // The fake profile store is independent of the fake disk lifecycle.
    auth.onSignIn = { AuthUser("other") }
    profiles.seed(UserProfile("other", Role.PATIENT, "Sam"))
    auth.signInWithGoogle("two")
    advanceUntilIdle()
    assertEquals("Sam", (reopened.uiState.value as ProfileUiState.Loaded).details.firstName)
    assertEquals(ProfileUiState.SignOutComplete, vm.uiState.value)
  }
}
