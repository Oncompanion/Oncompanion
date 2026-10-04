package com.github.se.oncompanion.ui.auth

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val user = AuthUser(uid = "uid-1", email = "a@b.c", displayName = "Alex Doe")

  private val returningProfile =
      UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex")

  private lateinit var repository: FakeAuthRepository
  private lateinit var profiles: FakeUserProfileRepository
  private lateinit var viewModel: SignInViewModel

  /** Number of times the token lambda passed to signIn was called. */
  private var tokenRequests = 0

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    repository = FakeAuthRepository(onSignIn = { user })
    profiles = FakeUserProfileRepository()
    viewModel = SignInViewModel(repository, profiles)
    tokenRequests = 0
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun token(value: String = "token-1"): suspend () -> String = {
    tokenRequests++
    value
  }

  private fun failingToken(reason: GoogleSignInException.Reason): suspend () -> String = {
    tokenRequests++
    throw GoogleSignInException(reason)
  }

  @Test
  fun initialState_isIdle() {
    assertEquals(SignInUiState(), viewModel.uiState.value)
    assertFalse(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.error)
    assertNull(viewModel.uiState.value.signedInUser)
    assertNull(viewModel.uiState.value.next)
  }

  @Test
  fun signIn_isLoadingUntilItCompletes() = runTest {
    val pendingToken = CompletableDeferred<String>()
    viewModel.signIn { pendingToken.await() }

    assertTrue(viewModel.uiState.value.isLoading)
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.signedInUser)

    pendingToken.complete("token-1")
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isLoading)
    assertEquals(AfterSignIn.ONBOARDING, viewModel.uiState.value.next)
  }

  @Test
  fun signIn_success_newUser_passesTheTokenToTheRepositoryAndGoesToOnboarding() = runTest {
    viewModel.signIn(token("the-google-token"))
    advanceUntilIdle()

    assertEquals(listOf("the-google-token"), repository.signInTokens)
    assertEquals(
        SignInUiState(
            isLoading = false,
            error = null,
            signedInUser = user,
            next = AfterSignIn.ONBOARDING,
        ),
        viewModel.uiState.value,
    )
  }

  @Test
  fun signIn_success_returningUser_goesToOverview() = runTest {
    profiles.seed(returningProfile)

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(
        SignInUiState(
            isLoading = false,
            error = null,
            signedInUser = user,
            next = AfterSignIn.OVERVIEW,
        ),
        viewModel.uiState.value,
    )
  }

  @Test
  fun signIn_looksUpTheProfileOfTheSignedInUser() = runTest {
    // A profile exists, but for someone else: the signed-in user is new.
    profiles.seed(returningProfile.copy(uid = "someone-else"))
    val recording = RecordingProfileRepository(profiles)
    viewModel = SignInViewModel(repository, recording)

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(listOf(user.uid), recording.requestedUids)
    assertEquals(AfterSignIn.ONBOARDING, viewModel.uiState.value.next)
  }

  @Test
  fun signIn_doesNotCheckTheProfileWhenSignInFails() = runTest {
    val recording = RecordingProfileRepository(profiles)
    viewModel = SignInViewModel(repository, recording)
    repository.onSignIn = { throw IllegalStateException("rejected token") }

    viewModel.signIn(token())
    advanceUntilIdle()

    assertTrue(recording.requestedUids.isEmpty())
    assertNull(viewModel.uiState.value.next)
  }

  @Test
  fun signIn_isLoadingWhileTheProfileIsChecked() = runTest {
    val pendingProfile = CompletableDeferred<UserProfile?>()
    viewModel = SignInViewModel(repository, PendingProfileRepository(pendingProfile))

    viewModel.signIn(token())
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoading)
    assertNull(viewModel.uiState.value.next)
    assertNull(viewModel.uiState.value.error)
    assertEquals(listOf("token-1"), repository.signInTokens)

    // A second tap while the profile is checked is ignored too.
    viewModel.signIn(token("second"))
    advanceUntilIdle()
    assertEquals(1, tokenRequests)

    pendingProfile.complete(returningProfile)
    advanceUntilIdle()
    assertEquals(
        SignInUiState(signedInUser = user, next = AfterSignIn.OVERVIEW),
        viewModel.uiState.value,
    )
  }

  @Test
  fun signIn_profileCheckOffline_showsNoConnectionError() = runTest {
    profiles.getProfileError =
        FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_CONNECTION), viewModel.uiState.value)
  }

  @Test
  fun signIn_profileCheckIoError_showsNoConnectionError() = runTest {
    profiles.getProfileError = IOException("offline")

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_CONNECTION), viewModel.uiState.value)
  }

  @Test
  fun signIn_profileCheckDenied_showsFailedError() = runTest {
    profiles.getProfileError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.FAILED), viewModel.uiState.value)
  }

  @Test
  fun signIn_profileCheckUnexpectedError_showsFailedError() = runTest {
    profiles.getProfileError = IllegalStateException("boom")

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.FAILED), viewModel.uiState.value)
  }

  @Test
  fun signIn_canBeRetriedAfterAProfileCheckFailure() = runTest {
    profiles.getProfileError =
        FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    viewModel.signIn(token())
    advanceUntilIdle()
    assertEquals(SignInError.NO_CONNECTION, viewModel.uiState.value.error)

    profiles.getProfileError = null
    profiles.seed(returningProfile)
    viewModel.signIn(token("retry-token"))
    advanceUntilIdle()

    assertEquals(
        SignInUiState(signedInUser = user, next = AfterSignIn.OVERVIEW),
        viewModel.uiState.value,
    )
    assertEquals(listOf("token-1", "retry-token"), repository.signInTokens)
  }

  @Test
  fun signIn_whileInProgress_isIgnored() = runTest {
    val pendingToken = CompletableDeferred<String>()
    viewModel.signIn {
      tokenRequests++
      pendingToken.await()
    }
    advanceUntilIdle()
    viewModel.signIn(token("second"))
    advanceUntilIdle()
    assertEquals(1, tokenRequests)

    pendingToken.complete("first")
    advanceUntilIdle()
    assertEquals(1, tokenRequests)
    assertEquals(listOf("first"), repository.signInTokens)
    assertEquals(user, viewModel.uiState.value.signedInUser)
    assertEquals(AfterSignIn.ONBOARDING, viewModel.uiState.value.next)
  }

  @Test
  fun signIn_calledTwiceImmediately_onlyRunsOnce() = runTest {
    viewModel.signIn(token("first"))
    viewModel.signIn(token("second"))
    advanceUntilIdle()

    assertEquals(1, tokenRequests)
    assertEquals(listOf("first"), repository.signInTokens)
  }

  @Test
  fun signIn_whileRepositoryIsSigningIn_isIgnored() = runTest {
    val pendingUser = CompletableDeferred<AuthUser>()
    repository.onSignIn = { pendingUser.await() }
    viewModel.signIn(token("first"))
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.isLoading)

    viewModel.signIn(token("second"))
    advanceUntilIdle()
    assertEquals(1, tokenRequests)
    assertEquals(listOf("first"), repository.signInTokens)

    pendingUser.complete(user)
    advanceUntilIdle()
    assertFalse(viewModel.uiState.value.isLoading)
    assertEquals(user, viewModel.uiState.value.signedInUser)
  }

  @Test
  fun signIn_cancelledByUser_showsNoErrorAndDoesNotCallRepository() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.CANCELLED))
    advanceUntilIdle()

    assertEquals(SignInUiState(), viewModel.uiState.value)
    assertTrue(repository.signInTokens.isEmpty())
  }

  @Test
  fun signIn_noGoogleAccount_showsNoGoogleAccountError() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.NO_ACCOUNT))
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_GOOGLE_ACCOUNT), viewModel.uiState.value)
    assertTrue(repository.signInTokens.isEmpty())
  }

  @Test
  fun signIn_googleNetworkError_showsNoConnectionErrorAndDoesNotCallRepository() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.NETWORK))
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_CONNECTION), viewModel.uiState.value)
    assertTrue(repository.signInTokens.isEmpty())
  }

  @Test
  fun signIn_canBeRetriedAfterAGoogleNetworkError() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.NETWORK))
    advanceUntilIdle()

    viewModel.signIn(token("retry-token"))
    advanceUntilIdle()

    assertEquals(
        SignInUiState(signedInUser = user, next = AfterSignIn.ONBOARDING),
        viewModel.uiState.value,
    )
    assertEquals(listOf("retry-token"), repository.signInTokens)
  }

  @Test
  fun signIn_googleFailure_showsFailedError() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.FAILED))
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.FAILED), viewModel.uiState.value)
    assertTrue(repository.signInTokens.isEmpty())
  }

  @Test
  fun signIn_firebaseNetworkError_showsNoConnectionError() = runTest {
    repository.onSignIn = { throw FirebaseNetworkException("offline") }
    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_CONNECTION), viewModel.uiState.value)
  }

  @Test
  fun signIn_ioError_showsNoConnectionError() = runTest {
    repository.onSignIn = { throw UnknownHostException("no host") }
    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.NO_CONNECTION), viewModel.uiState.value)
  }

  @Test
  fun signIn_otherRepositoryError_showsFailedError() = runTest {
    repository.onSignIn = { throw IllegalStateException("rejected token") }
    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(SignInUiState(error = SignInError.FAILED), viewModel.uiState.value)
  }

  @Test
  fun signIn_afterAnError_clearsTheErrorWhileLoadingAndOnSuccess() = runTest {
    repository.onSignIn = { throw IOException("offline") }
    viewModel.signIn(token())
    advanceUntilIdle()
    assertEquals(SignInError.NO_CONNECTION, viewModel.uiState.value.error)

    repository.onSignIn = { user }
    viewModel.signIn(token("retry-token"))
    assertNull(viewModel.uiState.value.error)
    assertTrue(viewModel.uiState.value.isLoading)
    advanceUntilIdle()

    assertEquals(
        SignInUiState(signedInUser = user, next = AfterSignIn.ONBOARDING),
        viewModel.uiState.value,
    )
    assertEquals(listOf("token-1", "retry-token"), repository.signInTokens)
  }

  @Test
  fun signIn_canBeRetriedAfterAFailure() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.FAILED))
    advanceUntilIdle()

    viewModel.signIn(token())
    advanceUntilIdle()

    assertEquals(2, tokenRequests)
    assertEquals(user, viewModel.uiState.value.signedInUser)
    assertEquals(AfterSignIn.ONBOARDING, viewModel.uiState.value.next)
  }

  @Test
  fun clearError_onlyRemovesTheError() = runTest {
    viewModel.signIn(failingToken(GoogleSignInException.Reason.NO_ACCOUNT))
    advanceUntilIdle()
    assertEquals(SignInError.NO_GOOGLE_ACCOUNT, viewModel.uiState.value.error)

    viewModel.clearError()

    assertEquals(SignInUiState(), viewModel.uiState.value)
  }

  @Test
  fun clearError_keepsTheSignedInUser() = runTest {
    viewModel.signIn(token())
    advanceUntilIdle()

    viewModel.clearError()

    assertEquals(
        SignInUiState(signedInUser = user, next = AfterSignIn.ONBOARDING),
        viewModel.uiState.value,
    )
  }
}

/** Delegates to [delegate] and records the uids passed to [getProfile]. */
private class RecordingProfileRepository(private val delegate: UserProfileRepository) :
    UserProfileRepository by delegate {
  val requestedUids = mutableListOf<String>()

  override suspend fun getProfile(uid: String): UserProfile? {
    requestedUids += uid
    return delegate.getProfile(uid)
  }
}

/** A [UserProfileRepository] whose [getProfile] suspends until [result] completes. */
private class PendingProfileRepository(private val result: CompletableDeferred<UserProfile?>) :
    UserProfileRepository {
  override suspend fun getProfile(uid: String): UserProfile? = result.await()

  override fun observeProfile(uid: String): Flow<UserProfile?> = flowOf(null)

  override suspend fun createProfile(profile: UserProfile) = error("unused")

  override suspend fun updateProfile(profile: UserProfile) = error("unused")
}
