// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.domain.auth.SignOutState
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.ui.onboarding.AccountSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

/** "Use another account" outside onboarding, e.g. on the account check (#204). */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountSwitchViewModelTest {

  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** A view model whose sign-out runs on [local], with someone signed in. */
  private fun switching(
      local: FakeLocalSessionRepository = FakeLocalSessionRepository()
  ): Pair<AccountSwitchViewModel, SignOut> {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "anne") })
    runBlocking { auth.signInWithGoogle("token") }
    val signOut = SignOut(auth, local).apply { initialize(FakeCleanupJournal()) }
    return AccountSwitchViewModel(signOut) to signOut
  }

  @Test
  fun useAnotherAccount_signsOutOnce_andForgetsTheChosenAccount() = runTest {
    val (vm, _) = switching()
    advanceUntilIdle()
    var cleared = 0

    vm.useAnotherAccount { cleared++ }
    assertEquals(AccountSwitch.SIGNING_OUT, vm.state.value)
    // A second tap while signing out does nothing
    vm.useAnotherAccount { cleared++ }
    advanceUntilIdle()

    assertEquals(AccountSwitch.DONE, vm.state.value)
    assertEquals(1, cleared)
  }

  @Test
  fun changesThatMayNotBeSaved_askFirst_thenStayOrDiscard() = runTest {
    for (discard in listOf(false, true)) {
      val local = FakeLocalSessionRepository().apply { synchronizeAction = { error("offline") } }
      val (vm, signOut) = switching(local)
      advanceUntilIdle()

      vm.useAnotherAccount {}
      advanceUntilIdle()
      assertEquals(AccountSwitch.CONFIRMING_DISCARD, vm.state.value)

      if (discard) {
        vm.discardChangesAndSwitch {}
        advanceUntilIdle()
        assertEquals(AccountSwitch.DONE, vm.state.value)
        assertFalse(local.offlineChanges)
      } else {
        vm.stayOnThisAccount()
        advanceUntilIdle()
        assertNull(vm.state.value)
        assertTrue(local.offlineChanges)
        assertEquals(SignOutState.IDLE, signOut.state.value)
      }
      SessionAccess.reopen()
    }
  }

  @Test
  fun discardOrStay_doNothingUnlessAsked() = runTest {
    val (vm, signOut) = switching()
    advanceUntilIdle()

    vm.discardChangesAndSwitch {}
    vm.stayOnThisAccount()
    advanceUntilIdle()

    assertNull(vm.state.value)
    assertEquals(SignOutState.IDLE, signOut.state.value)
  }
}
