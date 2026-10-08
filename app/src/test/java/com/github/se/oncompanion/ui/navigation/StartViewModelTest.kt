package com.github.se.oncompanion.ui.navigation

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val user = AuthUser(uid = "uid-42", email = "a@b.c", displayName = "Alex Doe")
  private val profile = UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex")

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** [AuthRepository] whose [currentUser] is computed by [current] (which may throw). */
  private class StubAuthRepository(private val current: () -> AuthUser?) : AuthRepository {
    override val currentUser: AuthUser?
      get() = current()

    override fun observeCurrentUser(): Flow<AuthUser?> = emptyFlow()

    override suspend fun signInWithGoogle(idToken: String): AuthUser =
        throw UnsupportedOperationException("not used at launch")

    override fun signOut() = throw UnsupportedOperationException("not used at launch")
  }

  /**
   * [UserProfileRepository] whose [getProfile] runs [onGet] and [getCachedProfile] runs [onCache]
   * (by default the cache doesn't know), recording every requested uid.
   */
  private class RecordingProfileRepository(
      private val onCache: suspend (uid: String) -> UserProfile? = { null },
      private val onGet: suspend (uid: String) -> UserProfile?,
  ) : UserProfileRepository {
    val requestedUids = mutableListOf<String>()
    val cachedUids = mutableListOf<String>()

    override suspend fun getProfile(uid: String): UserProfile? {
      requestedUids += uid
      return onGet(uid)
    }

    override suspend fun getCachedProfile(uid: String): UserProfile? {
      cachedUids += uid
      return onCache(uid)
    }

    override fun observeProfile(uid: String): Flow<UserProfile?> =
        throw UnsupportedOperationException("not used at launch")

    override suspend fun createProfile(profile: UserProfile) =
        throw UnsupportedOperationException("not used at launch")

    override suspend fun updateProfile(profile: UserProfile) =
        throw UnsupportedOperationException("not used at launch")
  }

  private fun signedIn() = StubAuthRepository { user }

  @Test
  fun defaultProfileTimeout_isFiveSeconds() {
    assertEquals(5_000L, StartViewModel.DEFAULT_PROFILE_TIMEOUT_MILLIS)
  }

  @Test
  fun signedOut_startsOnAuth_withoutQueryingProfile() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { profile }

        val viewModel = StartViewModel(StubAuthRepository { null }, profiles)
        advanceUntilIdle()

        assertEquals(Route.AUTH, viewModel.startRoute.value)
        assertEquals(emptyList<String>(), profiles.requestedUids)
      }

  @Test
  fun readingCurrentUserThrows_startsOnAuth_withoutCrashing() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { profile }
        val auth = StubAuthRepository {
          throw IllegalStateException("Default FirebaseApp is not initialized")
        }

        val viewModel = StartViewModel(auth, profiles)
        advanceUntilIdle()

        assertEquals(Route.AUTH, viewModel.startRoute.value)
        assertEquals(emptyList<String>(), profiles.requestedUids)
      }

  @Test
  fun signedInWithProfile_startsOnOverview_afterAskingForThatUsersProfile() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { uid -> profile.takeIf { uid == user.uid } }

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.requestedUids)
      }

  @Test
  fun cachedProfile_startsOnOverviewAtOnce_withoutAskingTheServer() =
      runTest(dispatcher) {
        // The server would never answer (slow connection): the cached profile must be enough
        val profiles =
            RecordingProfileRepository(
                onCache = { uid -> profile.takeIf { uid == user.uid } },
                onGet = { awaitCancellation() },
            )

        val viewModel = StartViewModel(signedIn(), profiles)
        runCurrent()

        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.cachedUids)
        assertEquals(emptyList<String>(), profiles.requestedUids)
      }

  @Test
  fun cacheDoesNotKnow_asksTheServer_andItsAnswerDecides() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository(onCache = { null }, onGet = { null })

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        // Not in the cache is unknown, not "no profile": only the server's null means onboarding
        assertEquals(Route.ONBOARDING, viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.cachedUids)
        assertEquals(listOf(user.uid), profiles.requestedUids)
      }

  @Test
  fun cacheReadFails_asksTheServer() =
      runTest(dispatcher) {
        val profiles =
            RecordingProfileRepository(
                onCache = { throw RuntimeException("cache unavailable") },
                onGet = { null },
            )

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        assertEquals(Route.ONBOARDING, viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.requestedUids)
      }

  @Test
  fun cacheReadNeverReturns_timeoutStillApplies() =
      runTest(dispatcher) {
        val profiles =
            RecordingProfileRepository(onCache = { awaitCancellation() }, onGet = { profile })

        val viewModel = StartViewModel(signedIn(), profiles, profileTimeoutMillis = 1_000L)
        advanceTimeBy(999L)
        runCurrent()
        assertNull(viewModel.startRoute.value)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun signedInWithoutProfile_startsOnOnboarding() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { null }

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        assertEquals(Route.ONBOARDING, viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.requestedUids)
      }

  @Test
  fun signedIn_profileReadFailsOffline_startsOnOverview() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository {
          throw FirebaseFirestoreException(
              "Failed to get document because the client is offline.",
              FirebaseFirestoreException.Code.UNAVAILABLE,
          )
        }

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun signedIn_profileReadThrowsUnexpectedException_startsOnOverview() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { throw RuntimeException("boom") }

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceUntilIdle()

        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun signedIn_profileReadNeverReturns_staysUndecidedUntilTimeout_thenStartsOnOverview() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { awaitCancellation() }

        val viewModel = StartViewModel(signedIn(), profiles, profileTimeoutMillis = 1_000L)
        runCurrent()
        assertNull(viewModel.startRoute.value)
        assertEquals(listOf(user.uid), profiles.requestedUids)

        advanceTimeBy(999L)
        runCurrent()
        assertNull(viewModel.startRoute.value)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun signedIn_profileReadNeverReturns_usesDefaultTimeout() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository { awaitCancellation() }

        val viewModel = StartViewModel(signedIn(), profiles)
        advanceTimeBy(StartViewModel.DEFAULT_PROFILE_TIMEOUT_MILLIS - 1)
        runCurrent()
        assertNull(viewModel.startRoute.value)

        advanceTimeBy(2L)
        runCurrent()
        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun signedIn_slowProfileReadWithinTimeout_usesItsResult() =
      runTest(dispatcher) {
        val profiles = RecordingProfileRepository {
          delay(4_000L)
          null
        }

        val viewModel = StartViewModel(signedIn(), profiles, profileTimeoutMillis = 5_000L)
        advanceTimeBy(3_999L)
        runCurrent()
        assertNull(viewModel.startRoute.value)

        advanceUntilIdle()
        assertEquals(Route.ONBOARDING, viewModel.startRoute.value)
      }

  @Test
  fun startRoute_isNullWhileDeciding_thenSetOnceProfileArrives() =
      runTest(dispatcher) {
        val answer = CompletableDeferred<UserProfile?>()
        val profiles = RecordingProfileRepository { answer.await() }

        val viewModel = StartViewModel(signedIn(), profiles, profileTimeoutMillis = 10_000L)
        assertNull(viewModel.startRoute.value)
        runCurrent()
        assertNull(viewModel.startRoute.value)

        answer.complete(profile)
        runCurrent()
        assertEquals(Route.OVERVIEW, viewModel.startRoute.value)
      }

  @Test
  fun decisionIsMadeOnce_atCreation() =
      runTest(dispatcher) {
        val auth = signedIn()
        val profiles = RecordingProfileRepository { profile }

        val viewModel = StartViewModel(auth, profiles)
        advanceUntilIdle()
        // Reading the state again must not trigger a new decision.
        repeat(3) { assertEquals(Route.OVERVIEW, viewModel.startRoute.value) }
        advanceUntilIdle()

        assertEquals(listOf(user.uid), profiles.requestedUids)
      }
}
