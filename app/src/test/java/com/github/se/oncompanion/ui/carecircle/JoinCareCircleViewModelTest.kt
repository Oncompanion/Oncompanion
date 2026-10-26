// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.RedeemResult
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.CompletableDeferred
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

/** Waits for [gate] (if set) or throws [error] (if set) before delegating [redeem]. */
private class ControlledInviteRepository(private val delegate: FakeCareCircleInviteRepository) :
    CareCircleInviteRepository by delegate {
  var gate: CompletableDeferred<Unit>? = null
  var error: Exception? = null
  val redeemed = mutableListOf<Triple<String, String, String?>>()

  override suspend fun redeem(
      inviteCode: String,
      caregiverUid: String,
      caregiverEmail: String?,
  ): RedeemResult {
    redeemed += Triple(inviteCode, caregiverUid, caregiverEmail)
    gate?.await()
    error?.let { throw it }
    return delegate.redeem(inviteCode, caregiverUid, caregiverEmail)
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class JoinCareCircleViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val caregiver = AuthUser(uid = "caregiver", email = "sam@example.com")
  private val code = "ABCDEFGHJK"
  private val invite =
      CareCircleInvite(
          inviteCode = code,
          ownerUid = "patient",
          ownerFirstName = "Marie",
          details = CareCircleMemberDetails(firstName = "Sam"),
      )

  private lateinit var auth: FakeAuthRepository
  private lateinit var fakeInvites: FakeCareCircleInviteRepository
  private lateinit var invites: ControlledInviteRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    auth = FakeAuthRepository(onSignIn = { caregiver })
    runBlocking { auth.signInWithGoogle("token") }
    fakeInvites = FakeCareCircleInviteRepository()
    fakeInvites.setInvites(listOf(invite))
    invites = ControlledInviteRepository(fakeInvites)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel() = JoinCareCircleViewModel(auth, invites)

  private val JoinCareCircleViewModel.state
    get() = uiState.value

  // --- JoinCareCircleUiState ---

  @Test
  fun canJoin_needsTenLettersOrDigits_ignoringDashesAndSpaces() {
    assertFalse(JoinCareCircleUiState().canJoin)
    assertFalse(JoinCareCircleUiState(code = "ABCDE-FGHJ").canJoin)
    assertTrue(JoinCareCircleUiState(code = "ABCDEFGHJK").canJoin)
    assertTrue(JoinCareCircleUiState(code = " abcde-fghjk ").canJoin)
    assertFalse(JoinCareCircleUiState(code = "ABCDEFGHJKM").canJoin)
  }

  @Test
  fun canJoin_isFalseWhileJoiningAndOnceJoined() {
    assertFalse(JoinCareCircleUiState(code = code, isJoining = true).canJoin)
    assertFalse(JoinCareCircleUiState(code = code, joinedPatientFirstName = "Marie").canJoin)
    assertTrue(JoinCareCircleUiState(joinedPatientFirstName = "Marie").hasJoined)
    assertFalse(JoinCareCircleUiState().hasJoined)
  }

  // --- onCodeChange ---

  @Test
  fun onCodeChange_storesTheCodeAsTyped() {
    val vm = viewModel()
    vm.onCodeChange("abcde-fghjk")
    assertEquals("abcde-fghjk", vm.state.code)
    assertTrue(vm.state.canJoin)
  }

  @Test
  fun onCodeChange_cutsVeryLongInput() {
    val vm = viewModel()
    vm.onCodeChange("A".repeat(40))
    assertEquals(16, vm.state.code.length)
  }

  @Test
  fun onCodeChange_clearsTheLastError() = runTest {
    val vm = viewModel()
    vm.onCodeChange("ZZZZZZZZZZ")
    vm.join()
    advanceUntilIdle()
    assertEquals(JoinCareCircleError.INVALID_CODE, vm.state.error)

    vm.onCodeChange("ZZZZZZZZZ")
    assertNull(vm.state.error)
  }

  // --- join ---

  @Test
  fun join_validCode_joinsWithTheCaregiversUidAndEmail() = runTest {
    val vm = viewModel()
    vm.onCodeChange("abcde-fghjk")
    vm.join()
    advanceUntilIdle()

    assertEquals(listOf(Triple("abcde-fghjk", "caregiver", "sam@example.com")), invites.redeemed)
    assertEquals("Marie", vm.state.joinedPatientFirstName)
    assertFalse(vm.state.wasAlreadyMember)
    assertFalse(vm.state.isJoining)
    assertNull(vm.state.error)
    assertEquals("patient", fakeInvites.joinedCircles("caregiver").single().patientUid)
  }

  @Test
  fun join_alreadyMember_countsAsJoined() = runTest {
    fakeInvites.setInvites(listOf(invite, invite.copy(inviteCode = "MNPQRSTUVW")))
    val first = viewModel()
    first.onCodeChange(code)
    first.join()
    advanceUntilIdle()

    val vm = viewModel()
    vm.onCodeChange("MNPQRSTUVW")
    vm.join()
    advanceUntilIdle()

    assertEquals("Marie", vm.state.joinedPatientFirstName)
    assertTrue(vm.state.wasAlreadyMember)
    assertTrue(vm.state.hasJoined)
  }

  @Test
  fun join_unknownCode_showsInvalidCode_andStaysOnTheForm() = runTest {
    val vm = viewModel()
    vm.onCodeChange("ZZZZZZZZZZ")
    vm.join()
    advanceUntilIdle()

    assertEquals(JoinCareCircleError.INVALID_CODE, vm.state.error)
    assertFalse(vm.state.hasJoined)
    assertFalse(vm.state.isJoining)
    assertTrue(vm.state.canJoin)
  }

  @Test
  fun join_offline_showsOffline_andCanBeRetried() = runTest {
    fakeInvites.isOffline = true
    val vm = viewModel()
    vm.onCodeChange(code)
    vm.join()
    advanceUntilIdle()
    assertEquals(JoinCareCircleError.OFFLINE, vm.state.error)

    fakeInvites.isOffline = false
    vm.join()
    advanceUntilIdle()
    assertNull(vm.state.error)
    assertEquals("Marie", vm.state.joinedPatientFirstName)
  }

  @Test
  fun join_unexpectedFailure_showsFailed() = runTest {
    invites.error = IllegalStateException("boom")
    val vm = viewModel()
    vm.onCodeChange(code)
    vm.join()
    advanceUntilIdle()

    assertEquals(JoinCareCircleError.FAILED, vm.state.error)
    assertFalse(vm.state.isJoining)
    assertFalse(vm.state.hasJoined)
  }

  @Test
  fun join_notSignedIn_showsNotSignedIn_andRedeemsNothing() = runTest {
    auth.signOut()
    val vm = viewModel()
    vm.onCodeChange(code)
    vm.join()
    advanceUntilIdle()

    assertEquals(JoinCareCircleError.NOT_SIGNED_IN, vm.state.error)
    assertTrue(invites.redeemed.isEmpty())
  }

  @Test
  fun join_incompleteCode_doesNothing() = runTest {
    val vm = viewModel()
    vm.onCodeChange("ABC")
    val before = vm.state
    vm.join()
    advanceUntilIdle()

    assertEquals(before, vm.state)
    assertTrue(invites.redeemed.isEmpty())
  }

  @Test
  fun join_whileJoining_ignoresASecondJoinAndCodeChanges() = runTest {
    val gate = CompletableDeferred<Unit>()
    invites.gate = gate
    val vm = viewModel()
    vm.onCodeChange(code)
    vm.join()
    advanceUntilIdle()
    assertTrue(vm.state.isJoining)
    assertFalse(vm.state.canJoin)

    vm.join()
    vm.onCodeChange("OTHER")
    advanceUntilIdle()
    assertEquals(1, invites.redeemed.size)
    assertEquals(code, vm.state.code)

    gate.complete(Unit)
    advanceUntilIdle()
    assertFalse(vm.state.isJoining)
    assertTrue(vm.state.hasJoined)
  }

  @Test
  fun join_onceJoined_codeChangesAreIgnored() = runTest {
    val vm = viewModel()
    vm.onCodeChange(code)
    vm.join()
    advanceUntilIdle()

    vm.onCodeChange("")
    assertEquals(code, vm.state.code)
    assertTrue(vm.state.hasJoined)
  }
}
