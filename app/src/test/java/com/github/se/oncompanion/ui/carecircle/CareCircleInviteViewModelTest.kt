// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import androidx.lifecycle.SavedStateHandle
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.Screen
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareCircleInviteViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val ownerUid = "owner"
  private val inviteCode = "K7M2PQ9XRT"

  /** "Now" when each test starts, so expiry never depends on the real time. */
  private val now = Instant.parse("2026-10-12T10:00:00Z")

  /** How far the clock moved without the timer seeing it, like a phone that slept. */
  private var clockJump = Duration.ZERO

  /** Follows the virtual time of [dispatcher], so `advanceTimeBy` also moves "now". */
  private val clock =
      object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant =
            now.plusMillis(dispatcher.scheduler.currentTime).plus(clockJump)
      }

  /** Julie's invitation, created [createdAgo] before "now", or not on the server yet if null. */
  private fun julie(createdAgo: Duration? = Duration.ofDays(1)) =
      CareCircleInvite(
          inviteCode = inviteCode,
          ownerUid = ownerUid,
          ownerFirstName = "Anne",
          details =
              CareCircleMemberDetails(
                  firstName = "Julie",
                  relationship = Relationship.SISTER,
                  permissions = setOf(CarePermission.PLANNING),
              ),
          email = "julie@example.com",
          createdAt = createdAgo?.let { now.minus(it) },
      )

  private lateinit var circle: FakeCareCircleRepository
  private lateinit var invites: FakeCareCircleInviteRepository
  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() = runTest {
    Dispatchers.setMain(dispatcher)
    circle = FakeCareCircleRepository()
    invites = FakeCareCircleInviteRepository(circle)
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = ownerUid) })
    auth.signInWithGoogle("token")
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  /** The ViewModel as navigation creates it, with the invite code as route argument. */
  private fun createViewModel(
      code: String? = inviteCode,
      authRepository: AuthRepository = auth,
  ): CareCircleInviteViewModel {
    val args = if (code == null) emptyMap() else mapOf(Screen.CARE_CIRCLE_INVITE_ARG to code)
    return CareCircleInviteViewModel(SavedStateHandle(args), invites, circle, authRepository, clock)
  }

  private fun CareCircleInviteViewModel.invite() = uiState.value as CareCircleInviteUiState.Invite

  // ---- reading ----

  @Test
  fun initialState_isLoading() {
    assertEquals(CareCircleInviteUiState.Loading, createViewModel().uiState.value)
  }

  @Test
  fun pendingInvite_isShown() = runTest {
    invites.setInvites(listOf(julie()))
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.Invite(julie(), expired = false), viewModel.uiState.value)
  }

  @Test
  fun missingOrCancelledInvite_isNotFound() = runTest {
    invites.setInvites(listOf(julie()))
    val viewModel = createViewModel()
    runCurrent()
    // Cancelled from another device
    invites.cancelInvite(inviteCode)
    runCurrent()
    assertEquals(CareCircleInviteUiState.NotFound, viewModel.uiState.value)
  }

  @Test
  fun anotherPatientsInvite_isNotFound() = runTest {
    invites.setInvites(listOf(julie().copy(ownerUid = "someone-else")))
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.NotFound, viewModel.uiState.value)
  }

  @Test
  fun noInviteCode_isAnError() = runTest {
    val viewModel = createViewModel(code = null)
    runCurrent()
    assertEquals(CareCircleInviteUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun nobodySignedIn_isAnError() = runTest {
    val viewModel = createViewModel(authRepository = FakeAuthRepository())
    runCurrent()
    assertEquals(CareCircleInviteUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun readError_isAnError_andRetryReadsAgain() = runTest {
    invites.setInvites(listOf(julie()))
    invites.observeError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.Error, viewModel.uiState.value)

    invites.observeError = null
    viewModel.retry()
    assertEquals(CareCircleInviteUiState.Loading, viewModel.uiState.value)
    runCurrent()
    assertEquals(julie(), viewModel.invite().invite)
  }

  @Test
  fun membersReadError_isAnError() = runTest {
    invites.setInvites(listOf(julie()))
    circle.observeError = IllegalStateException("denied")
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.Error, viewModel.uiState.value)
  }

  // ---- joining ----

  @Test
  fun personRedeemsTheInviteCode_showsThemAsJoined() = runTest {
    // Not expired by the fake's real-time check: created just now on the server
    invites.setInvites(listOf(julie(createdAgo = null)))
    val viewModel = createViewModel()
    runCurrent()

    invites.redeem(inviteCode, "caregiver", "julie.d@gmail.com")
    runCurrent()

    val joined = viewModel.uiState.value as CareCircleInviteUiState.Joined
    assertEquals("caregiver", joined.member.uid)
    assertEquals("Julie", joined.member.firstName)
    assertEquals(setOf(CarePermission.PLANNING), joined.member.permissions)
  }

  @Test
  fun memberWhoJoinedWithThisInviteCode_isJoined_evenIfTheInviteIsGone() = runTest {
    val member = CareCircleMember(uid = "caregiver", firstName = "Julie", inviteCode = inviteCode)
    circle.setMembers(ownerUid, listOf(CareCircleMember(uid = "other", firstName = "Bob"), member))
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.Joined(member), viewModel.uiState.value)
  }

  @Test
  fun memberWithAnotherInviteCode_doesNotCountAsJoined() = runTest {
    circle.setMembers(
        ownerUid,
        listOf(CareCircleMember(uid = "bob", firstName = "Bob", inviteCode = "BBBBB23456")),
    )
    val viewModel = createViewModel()
    runCurrent()
    assertEquals(CareCircleInviteUiState.NotFound, viewModel.uiState.value)
  }

  // ---- expiry ----

  @Test
  fun inviteOlderThanItsValidity_isExpired() = runTest {
    invites.setInvites(listOf(julie(createdAgo = Duration.ofDays(8))))
    val viewModel = createViewModel()
    runCurrent()
    assertTrue(viewModel.invite().expired)
  }

  @Test
  fun unsyncedInvite_neverExpires() = runTest {
    invites.setInvites(listOf(julie(createdAgo = null).copy(isSynced = false)))
    val viewModel = createViewModel()
    runCurrent()
    assertFalse(viewModel.invite().expired)
  }

  @Test
  fun inviteExpiresWhileTheScreenIsOpen() = runTest {
    // One hour of validity left
    invites.setInvites(
        listOf(julie(createdAgo = CareCircleInvite.VALIDITY.minus(Duration.ofHours(1))))
    )
    val viewModel = createViewModel()
    runCurrent()
    assertFalse(viewModel.invite().expired)

    advanceTimeBy(1.hours + 1.seconds)
    assertTrue(viewModel.invite().expired)
  }

  @Test
  fun refreshExpiry_catchesUpWhenTheClockMovedWithoutTheTimer() = runTest {
    invites.setInvites(
        listOf(julie(createdAgo = CareCircleInvite.VALIDITY.minus(Duration.ofMinutes(5))))
    )
    val viewModel = createViewModel()
    runCurrent()
    assertFalse(viewModel.invite().expired)

    // The phone slept for 10 minutes: the clock moved, but the timer didn't fire
    clockJump = Duration.ofMinutes(10)
    viewModel.refreshExpiry()
    runCurrent()
    assertTrue(viewModel.invite().expired)
  }
}
