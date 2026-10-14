// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

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
import com.google.firebase.firestore.FirebaseFirestoreException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
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
class CareCircleViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val ownerUid = "uid-owner"

  private val sophie =
      CareCircleMember(
          uid = "sophie",
          firstName = "Sophie",
          familyName = "Dubois",
          relationship = Relationship.WIFE,
          permissions = CarePermission.entries.toSet(),
      )
  private val marc =
      CareCircleMember(
          uid = "marc",
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
      )

  /** "Now" for every test, so expiry never depends on the real time. */
  private val now = Instant.parse("2026-10-12T10:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)

  private fun invite(
      inviteCode: String,
      firstName: String,
      createdAt: Instant? = now.minus(Duration.ofDays(1)),
      ownerUid: String = this.ownerUid,
      isSynced: Boolean = true,
  ) =
      CareCircleInvite(
          inviteCode = inviteCode,
          ownerUid = ownerUid,
          ownerFirstName = "Anne",
          details =
              CareCircleMemberDetails(firstName = firstName, relationship = Relationship.DAUGHTER),
          createdAt = createdAt,
          isSynced = isSynced,
      )

  private val julie = invite("JJJJJ23456", "Julie")
  private val bruno = invite("BBBBB23456", "Bruno")

  private fun joined(vararg members: CareCircleMember) = members.map { CareCircleEntry.Joined(it) }

  private fun pending(vararg invites: CareCircleInvite) = invites.map {
    CareCircleEntry.Pending(it, expired = false)
  }

  private lateinit var repository: FakeCareCircleRepository
  private lateinit var invites: FakeCareCircleInviteRepository
  private lateinit var auth: FakeAuthRepository

  @Before
  fun setUp() = runTest {
    Dispatchers.setMain(dispatcher)
    repository = FakeCareCircleRepository()
    invites = FakeCareCircleInviteRepository()
    auth = FakeAuthRepository(onSignIn = { AuthUser(uid = ownerUid) })
    auth.signInWithGoogle("token")
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createViewModel(authRepository: AuthRepository = auth) =
      CareCircleViewModel(repository, invites, authRepository, clock)

  @Test
  fun initialState_isLoading() {
    val viewModel = createViewModel()
    assertEquals(CareCircleUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun emptyCircle_isEmpty() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Empty, viewModel.uiState.value)
    assertEquals(listOf(ownerUid), repository.observedOwners)
  }

  @Test
  fun circleWithMembers_listsThemSortedByName() = runTest {
    repository.setMembers(ownerUid, listOf(sophie, marc))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(joined(marc, sophie), emptyList()),
        viewModel.uiState.value,
    )
  }

  @Test
  fun onlyTheSignedInUsersCircleIsShown() = runTest {
    repository.setMembers("someone-else", listOf(sophie))
    invites.setInvites(listOf(invite("XXXXX23456", "Other", ownerUid = "someone-else")))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun changesToTheCircle_updateTheState() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Empty, viewModel.uiState.value)

    repository.setMembers(ownerUid, listOf(sophie))
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(joined(sophie), emptyList()), viewModel.uiState.value)

    repository.setMembers(ownerUid, emptyList())
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Empty, viewModel.uiState.value)
  }

  @Test
  fun invitesOnly_areListedAsPending_notEmpty() = runTest {
    invites.setInvites(listOf(julie, bruno))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(emptyList(), pending(bruno, julie)),
        viewModel.uiState.value,
    )
  }

  @Test
  fun membersAndInvites_areListedInTheirOwnSections() = runTest {
    repository.setMembers(ownerUid, listOf(sophie, marc))
    invites.setInvites(listOf(julie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(joined(marc, sophie), pending(julie)),
        viewModel.uiState.value,
    )
  }

  @Test
  fun usedInvites_areNotPending() = runTest {
    repository.setMembers(ownerUid, listOf(sophie))
    invites.setInvites(listOf(julie.copy(redeemerUid = "uid-julie")))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(joined(sophie), emptyList()), viewModel.uiState.value)
  }

  @Test
  fun anInviteThatIsJoined_movesFromPendingToMembers() = runTest {
    invites.setInvites(listOf(julie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(emptyList(), pending(julie)), viewModel.uiState.value)

    // What redeeming does: the invite is used and a member doc appears
    val julieMember = CareCircleMember(uid = "uid-julie", firstName = "Julie")
    invites.setInvites(listOf(julie.copy(redeemerUid = "uid-julie")))
    repository.setMembers(ownerUid, listOf(julieMember))
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(joined(julieMember), emptyList()),
        viewModel.uiState.value,
    )
  }

  @Test
  fun inviteOlderThanItsValidity_isExpired() = runTest {
    val expired = invite("EEEEE23456", "Old", createdAt = now.minus(Duration.ofDays(8)))
    val lastMoment =
        invite("FFFFF23456", "Last", createdAt = now.minus(Duration.ofDays(7)).plusSeconds(1))
    invites.setInvites(listOf(expired, lastMoment))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(
            emptyList(),
            listOf(
                CareCircleEntry.Pending(lastMoment, expired = false),
                CareCircleEntry.Pending(expired, expired = true),
            ),
        ),
        viewModel.uiState.value,
    )
  }

  @Test
  fun inviteNotOnTheServerYet_isNotExpired() = runTest {
    val notSynced = invite("GGGGG23456", "Local", createdAt = null, isSynced = false)
    invites.setInvites(listOf(notSynced))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(
        CareCircleUiState.CareCircleMembers(emptyList(), listOf(CareCircleEntry.Pending(notSynced, false))),
        viewModel.uiState.value,
    )
  }

  @Test
  fun invitesReadFailure_isError_evenWithMembers() = runTest {
    repository.setMembers(ownerUid, listOf(sophie))
    invites.observeError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun readFailure_isError() = runTest {
    repository.observeError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun nobodySignedIn_isError_withoutReadingAnyCircle() = runTest {
    auth.signOut()
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)
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
    val viewModel = createViewModel(failingAuth)
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)
  }

  @Test
  fun retry_afterError_showsLoadingThenTheCircle() = runTest {
    repository.observeError = IllegalStateException("boom")
    repository.setMembers(ownerUid, listOf(sophie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)

    repository.observeError = null
    viewModel.retry()
    assertEquals(CareCircleUiState.Loading, viewModel.uiState.value)
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(joined(sophie), emptyList()), viewModel.uiState.value)
    assertEquals(listOf(ownerUid, ownerUid), repository.observedOwners)
  }

  @Test
  fun retry_afterAnInvitesError_showsTheCircle() = runTest {
    invites.observeError = IllegalStateException("boom")
    invites.setInvites(listOf(julie))
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.Error, viewModel.uiState.value)

    invites.observeError = null
    viewModel.retry()
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(emptyList(), pending(julie)), viewModel.uiState.value)
  }

  @Test
  fun retry_stopsThePreviousObservation() = runTest {
    val viewModel = createViewModel()
    advanceUntilIdle()
    assertEquals(1, repository.activeObservations)
    viewModel.retry()
    advanceUntilIdle()
    // The first listener is gone, not just outrun by the new one
    assertEquals(1, repository.activeObservations)

    // Only the new observation updates the state: no stale "Empty" from the first one
    repository.setMembers(ownerUid, listOf(sophie))
    advanceUntilIdle()
    assertEquals(CareCircleUiState.CareCircleMembers(joined(sophie), emptyList()), viewModel.uiState.value)
  }
}
