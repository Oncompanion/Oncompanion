// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.common

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.session.RejectedWrites
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RejectedWriteViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private val rejectedWrites = RejectedWrites()
  private val auth = FakeAuthRepository()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    SessionAccess.reopen()
    Dispatchers.resetMain()
  }

  private fun signOut(scope: CoroutineScope) =
      SignOut(auth, FakeLocalSessionRepository(), scope).apply { initialize(FakeCleanupJournal()) }

  private suspend fun TestScope.signOutCompletely(signOut: SignOut) {
    auth.signInWithGoogle("alice")
    signOut.begin {}
    runCurrent()
    assertEquals(SignOutState.COMPLETE, signOut.state.value)
  }

  @Test
  fun startsWithTheRejectionsAlreadyReported() =
      runTest(dispatcher) {
        rejectedWrites.report()
        val viewModel = RejectedWriteViewModel(rejectedWrites, signOut(backgroundScope))

        assertEquals(RejectedWriteUiState(1), viewModel.uiState.value)
      }

  @Test
  fun eachRejectionIsCountedUntilTheUserIsTold() =
      runTest(dispatcher) {
        val viewModel = RejectedWriteViewModel(rejectedWrites, signOut(backgroundScope))
        runCurrent()
        assertEquals(RejectedWriteUiState(0), viewModel.uiState.value)

        rejectedWrites.report()
        rejectedWrites.report()
        runCurrent()
        assertEquals(RejectedWriteUiState(2), viewModel.uiState.value)

        viewModel.onShown(2)
        runCurrent()
        assertEquals(RejectedWriteUiState(0), viewModel.uiState.value)
      }

  @Test
  fun aRejectionReportedWhileTheMessageShowsIsNotLost() =
      runTest(dispatcher) {
        val viewModel = RejectedWriteViewModel(rejectedWrites, signOut(backgroundScope))
        rejectedWrites.report()
        runCurrent()
        // The snackbar is showing the first one when the second arrives
        rejectedWrites.report()

        viewModel.onShown(1)
        runCurrent()

        assertEquals(RejectedWriteUiState(1), viewModel.uiState.value)
      }

  @Test
  fun signingOutForgetsTheRejectionsOfThePreviousUser() =
      runTest(dispatcher) {
        val signOut = signOut(backgroundScope)
        val viewModel = RejectedWriteViewModel(rejectedWrites, signOut)
        rejectedWrites.report()
        runCurrent()

        signOutCompletely(signOut)
        runCurrent()

        assertEquals(RejectedWriteUiState(0), viewModel.uiState.value)
      }

  @Test
  fun aClearedViewModelNoLongerTakesPartInSignOut() =
      runTest(dispatcher) {
        val signOut = signOut(backgroundScope)
        val store = ViewModelStore()
        ViewModelProvider.create(
                store,
                viewModelFactory {
                  initializer { RejectedWriteViewModel(rejectedWrites, signOut) }
                },
            )[RejectedWriteViewModel::class]
        store.clear()
        rejectedWrites.report()

        signOutCompletely(signOut)

        // Only the snackbar of the session being closed forgets the rejections
        assertEquals(1, rejectedWrites.unseen.value)
      }
}
