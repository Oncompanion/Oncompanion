// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CareCircleRepository
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CareCircleMemberFormViewModelTest {

  private val dispatcher = StandardTestDispatcher()
  private val ownerUid = "uid-owner"

  private val invites = FakeCareCircleInviteRepository().apply { nextInviteCode = { "K7M2PQ9XRT" } }
  private val profiles =
      FakeUserProfileRepository().apply {
        seed(UserProfile(uid = ownerUid, role = Role.PATIENT, firstName = "Anne"))
      }
  private val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = ownerUid) })

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    runBlocking { auth.signInWithGoogle("token") }
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(authRepository: AuthRepository = auth) =
      CareCircleMemberFormViewModel(invites, profiles, authRepository, circle, clock = clock)

  /** Fills in a complete form: Julie Martin, daughter, julie@example.com. */
  private fun CareCircleMemberFormViewModel.fill() {
    onFirstNameChange("Julie")
    onFamilyNameChange("Martin")
    onRelationshipChange(Relationship.DAUGHTER)
    onEmailChange("julie@example.com")
  }

  private fun TestScope.saved(viewModel: CareCircleMemberFormViewModel) {
    viewModel.save()
    advanceUntilIdle()
  }

  @Test
  fun newForm_isEmpty_withEveryAccessOff_andCantBeSaved() {
    val state = viewModel().uiState.value
    assertEquals(CareCircleMemberFormUiState(), state)
    assertTrue(state.permissions.isEmpty())
    assertTrue(state.canAdd.isEmpty())
    assertNull(state.relationship)
    assertFalse(state.firstNameTouched)
    assertFalse(state.canSave)
  }

  @Test
  fun canSave_needsBothAFirstNameAndARelationship() {
    val viewModel = viewModel()
    viewModel.onFirstNameChange("Julie")
    assertTrue(viewModel.uiState.value.missingRelationship)
    assertFalse(viewModel.uiState.value.canSave)

    viewModel.onFirstNameChange("  ")
    viewModel.onRelationshipChange(Relationship.OTHER)
    assertTrue(viewModel.uiState.value.missingFirstName)
    assertFalse(viewModel.uiState.value.canSave)

    viewModel.onFirstNameChange("Julie")
    assertTrue(viewModel.uiState.value.canSave)
  }

  @Test
  fun firstName_isTouchedOnceChanged() {
    val viewModel = viewModel()
    viewModel.onFirstNameChange("J")
    viewModel.onFirstNameChange("")
    assertTrue(viewModel.uiState.value.firstNameTouched)
    assertTrue(viewModel.uiState.value.missingFirstName)
  }

  @Test
  fun invalidEmail_blocksSaving_andAnEmptyEmailDoesNot() {
    val viewModel = viewModel()
    viewModel.fill()
    viewModel.onEmailChange("julie@")
    assertTrue(viewModel.uiState.value.invalidEmail)
    assertFalse(viewModel.uiState.value.canSave)

    viewModel.onEmailChange("")
    assertFalse(viewModel.uiState.value.invalidEmail)
    assertTrue(viewModel.uiState.value.canSave)
  }

  @Test
  fun namesAndEmail_areCutToWhatTheServerAccepts() {
    val viewModel = viewModel()
    viewModel.onFirstNameChange("a".repeat(60))
    viewModel.onFamilyNameChange("b".repeat(60))
    viewModel.onEmailChange("c".repeat(300))
    val state = viewModel.uiState.value
    assertEquals(CareCircleMemberDetails.MAX_NAME_LENGTH, state.firstName.length)
    assertEquals(CareCircleMemberDetails.MAX_NAME_LENGTH, state.familyName.length)
    assertEquals(CareCircleInvite.MAX_EMAIL_LENGTH, state.email.length)
  }

  @Test
  fun permissions_turnOnAndOff() {
    val viewModel = viewModel()
    viewModel.onPermissionChange(CarePermission.PLANNING, true)
    viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
    viewModel.onPermissionChange(CarePermission.PLANNING, false)
    assertEquals(setOf(CarePermission.SYMPTOMS), viewModel.uiState.value.permissions)
  }

  @Test
  fun addingToASection_needsSeeingIt() {
    val viewModel = viewModel()
    viewModel.onCanAddChange(CarePermission.SYMPTOMS, true)
    assertTrue(viewModel.uiState.value.canAdd.isEmpty())

    viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
    viewModel.onCanAddChange(CarePermission.SYMPTOMS, true)
    assertEquals(setOf(CarePermission.SYMPTOMS), viewModel.uiState.value.canAdd)

    viewModel.onCanAddChange(CarePermission.SYMPTOMS, false)
    assertTrue(viewModel.uiState.value.canAdd.isEmpty())
  }

  @Test
  fun hidingASection_alsoStopsAddingToIt_andOnlyToIt() {
    val viewModel = viewModel()
    viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
    viewModel.onPermissionChange(CarePermission.PLANNING, true)
    viewModel.onCanAddChange(CarePermission.SYMPTOMS, true)
    viewModel.onCanAddChange(CarePermission.PLANNING, true)

    viewModel.onPermissionChange(CarePermission.PLANNING, false)
    assertEquals(setOf(CarePermission.SYMPTOMS), viewModel.uiState.value.canAdd)

    viewModel.onPermissionChange(CarePermission.SYMPTOMS, false)
    assertTrue(viewModel.uiState.value.canAdd.isEmpty())
    // Seeing it again doesn't bring adding back
    viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
    assertTrue(viewModel.uiState.value.canAdd.isEmpty())
  }

  @Test
  fun save_createsTheInvite_withThePatientsFirstName_trimmedDetailsAndEmail() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onFirstNameChange("  Julie ")
        viewModel.onFamilyNameChange(" Martin  ")
        viewModel.onRelationshipChange(Relationship.DAUGHTER)
        viewModel.onEmailChange(" julie@example.com ")
        viewModel.onPermissionChange(CarePermission.PLANNING, true)
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
        viewModel.onCanAddChange(CarePermission.SYMPTOMS, true)

        saved(viewModel)

        val invite = invites.created.single()
        assertEquals(ownerUid, invite.ownerUid)
        assertEquals("Anne", invite.ownerFirstName)
        assertEquals(
            CareCircleMemberDetails(
                firstName = "Julie",
                familyName = "Martin",
                relationship = Relationship.DAUGHTER,
                permissions = setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS),
                canAdd = setOf(CarePermission.SYMPTOMS),
            ),
            invite.details,
        )
        assertEquals("julie@example.com", invite.email)
        val state = viewModel.uiState.value
        assertEquals(SavePhase.SAVED, state.save)
        assertEquals("K7M2PQ9XRT", state.savedInviteCode)
        assertFalse(state.saveFailed)
        assertFalse(state.canSave)
      }

  @Test
  fun save_withoutLastNameOrEmail_storesThemAsMissing() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onFirstNameChange("Julie")
        viewModel.onFamilyNameChange("   ")
        viewModel.onRelationshipChange(Relationship.FRIEND)

        saved(viewModel)

        val invite = invites.created.single()
        assertNull(invite.details.familyName)
        assertNull(invite.email)
      }

  @Test
  fun save_whileIncomplete_doesNothing() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onFirstNameChange("Julie")
        saved(viewModel)
        assertTrue(invites.created.isEmpty())
        assertNotEquals(SavePhase.SAVING, viewModel.uiState.value.save)
      }

  @Test
  fun doubleTap_createsOneInvite() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.fill()
        viewModel.save()
        assertEquals(SavePhase.SAVING, viewModel.uiState.value.save)
        viewModel.save()
        advanceUntilIdle()
        assertEquals(1, invites.created.size)
      }

  @Test
  fun fields_cantChangeWhileSaving() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.fill()
        viewModel.save()
        viewModel.onFirstNameChange("Bruno")
        viewModel.onPermissionChange(CarePermission.EVENTS, true)
        advanceUntilIdle()
        assertEquals("Julie", invites.created.single().details.firstName)
        assertEquals("Julie", viewModel.uiState.value.firstName)
        assertTrue(viewModel.uiState.value.permissions.isEmpty())
      }

  @Test
  fun saveError_keepsTheDraft_andTheNextEditClearsIt() =
      runTest(dispatcher) {
        invites.createError = IllegalStateException("boom")
        val viewModel = viewModel()
        viewModel.fill()

        saved(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state.saveFailed)
        assertEquals(SavePhase.EDITING, state.save)
        assertEquals("Julie", state.firstName)
        assertEquals(Relationship.DAUGHTER, state.relationship)
        assertTrue(state.canSave)

        viewModel.onFamilyNameChange("Dupont")
        assertFalse(viewModel.uiState.value.saveFailed)
      }

  @Test
  fun saveError_canBeRetried() =
      runTest(dispatcher) {
        invites.createError = IllegalStateException("boom")
        val viewModel = viewModel()
        viewModel.fill()
        saved(viewModel)

        invites.createError = null
        saved(viewModel)

        assertEquals(1, invites.created.size)
        assertEquals(SavePhase.SAVED, viewModel.uiState.value.save)
      }

  @Test
  fun missingProfile_isASaveError() =
      runTest(dispatcher) {
        val viewModel =
            CareCircleMemberFormViewModel(invites, FakeUserProfileRepository(), auth, circle)
        viewModel.fill()
        saved(viewModel)
        assertTrue(viewModel.uiState.value.saveFailed)
        assertTrue(invites.created.isEmpty())
      }

  @Test
  fun unreadableProfile_isASaveError() =
      runTest(dispatcher) {
        profiles.getProfileError = IllegalStateException("boom")
        val viewModel = viewModel()
        viewModel.fill()
        saved(viewModel)
        assertTrue(viewModel.uiState.value.saveFailed)
        assertTrue(invites.created.isEmpty())
      }

  @Test
  fun nobodySignedIn_isASaveError() =
      runTest(dispatcher) {
        val viewModel = viewModel(authRepository = FakeAuthRepository())
        viewModel.fill()
        saved(viewModel)
        assertTrue(viewModel.uiState.value.saveFailed)
        assertTrue(invites.created.isEmpty())
      }

  @Test
  fun unreadableSignedInUser_isASaveError() =
      runTest(dispatcher) {
        val broken =
            object : AuthRepository by auth {
              override val currentUser: AuthUser?
                get() = throw IllegalStateException("boom")
            }
        val viewModel = viewModel(authRepository = broken)
        viewModel.fill()
        saved(viewModel)
        assertTrue(viewModel.uiState.value.saveFailed)
      }

  @Test
  fun cachedProfile_isUsedWithoutAskingTheServer() =
      runTest(dispatcher) {
        val cachedOnly =
            object : UserProfileRepository by profiles {
              override suspend fun getCachedProfile(uid: String) =
                  UserProfile(uid = uid, role = Role.PATIENT, firstName = "Annie")

              override suspend fun getProfile(uid: String) = error("Asked the server")
            }
        val viewModel = CareCircleMemberFormViewModel(invites, cachedOnly, auth, circle)
        viewModel.fill()
        saved(viewModel)
        assertEquals("Annie", invites.created.single().ownerFirstName)
      }

  @Test
  fun consumeSaved_returnsToEditingAndClearsTheInviteCode() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.fill()
        saved(viewModel)

        viewModel.consumeSaved()

        assertEquals(SavePhase.EDITING, viewModel.uiState.value.save)
        assertNull(viewModel.uiState.value.savedInviteCode)
      }

  // ---- inviting someone already in the circle (#257) ----
  // Who matches is tested on emailAlreadyInCircle; these tests check how the form uses it.

  private val now = Instant.parse("2026-11-04T10:00:00Z")

  /** How far the phone's clock jumped ahead, e.g. while it slept and no timer ran. */
  private var clockJump = Duration.ZERO

  /** [now] when each test starts, then moving with the test's virtual time and [clockJump]. */
  private val clock =
      object : Clock() {
        override fun instant(): Instant =
            now.plusMillis(dispatcher.scheduler.currentTime).plus(clockJump)

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this
      }

  private fun inviteFor(
      email: String?,
      firstName: String = "Paul",
      inviteCode: String = "INVITEPAUL",
      createdAt: Instant? = now,
  ) =
      CareCircleInvite(
          inviteCode = inviteCode,
          ownerUid = ownerUid,
          ownerFirstName = "Anne",
          details = CareCircleMemberDetails(firstName = firstName),
          email = email,
          createdAt = createdAt,
      )

  /**
   * The add form once it has read the circle. Not [advanceUntilIdle]: that would also run the
   * expiry timer, days ahead.
   */
  private fun TestScope.loadedViewModel() = viewModel().also { runCurrent() }

  /** Types [email], then lets the form compare it with the circle. */
  private fun TestScope.typeEmail(viewModel: CareCircleMemberFormViewModel, email: String) {
    viewModel.onEmailChange(email)
    runCurrent()
  }

  @Test
  fun anEmailAlreadyInTheCircle_isRefused_andTheFormNeverWritesForIt() =
      runTest(dispatcher) {
        val expired =
            inviteFor("paul@example.com", createdAt = now.minus(CareCircleInvite.VALIDITY))
        val cases: List<Pair<() -> Unit, EmailAlreadyInCircle>> =
            listOf(
                {
                  circle.setMembers(
                      ownerUid,
                      listOf(marc.copy(firstName = "Paul", email = "paul@example.com")),
                  )
                } to EmailAlreadyInCircle.Member("Paul"),
                {
                  invites.setInvites(listOf(inviteFor("paul@example.com")))
                } to EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
                // Not renewed from the form: its details may differ from what the form holds
                {
                  invites.setInvites(listOf(expired))
                } to EmailAlreadyInCircle.ExpiredInvite("Paul", "INVITEPAUL"),
            )
        for ((setUpCircle, expected) in cases) {
          invites.setInvites(emptyList())
          circle.setMembers(ownerUid, emptyList())
          setUpCircle()
          val viewModel = loadedViewModel()
          viewModel.fill()
          typeEmail(viewModel, "paul@example.com")

          assertEquals(expected, viewModel.uiState.value.duplicate)
          assertFalse("$expected", viewModel.uiState.value.canSave)

          viewModel.save()
          runCurrent()

          assertTrue("$expected", invites.created.isEmpty())
          assertEquals("$expected", SavePhase.EDITING, viewModel.uiState.value.save)
        }
        assertEquals(expired, invites.invite(expired.inviteCode))
      }

  @Test
  fun theTypedEmail_matchesWhateverItsCaseOrSpaces() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor(" Paul@EXAMPLE.com ")))
        val viewModel = loadedViewModel()
        viewModel.fill()
        for (typed in
            listOf(
                "paul@example.com",
                " paul@example.com  ",
                "PAUL@Example.COM",
                "\tPaul@example.com",
            )) {
          typeEmail(viewModel, typed)

          val state = viewModel.uiState.value
          assertEquals(
              typed,
              EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
              state.duplicate,
          )
          assertFalse(typed, state.canSave)
        }
      }

  @Test
  fun sendTappedBeforeTheCircleIsRead_stillRefusesADuplicate() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor("julie@example.com", firstName = "Julie")))
        // The circle hasn't been read yet: nothing is known to match, so Send is enabled
        val viewModel = viewModel()
        viewModel.fill()
        assertNull(viewModel.uiState.value.duplicate)
        assertTrue(viewModel.uiState.value.canSave)

        viewModel.save()
        runCurrent()

        assertTrue(invites.created.isEmpty())
        val state = viewModel.uiState.value
        assertEquals(SavePhase.EDITING, state.save)
        assertFalse(state.saveFailed)
        assertEquals(EmailAlreadyInCircle.PendingInvite("Julie", "INVITEPAUL"), state.duplicate)
      }

  @Test
  fun aCircleThatIsNeverRead_holdsUpSendForThreeSecondsAtMost() =
      runTest(dispatcher) {
        val neverRead: List<Flow<List<CareCircleMember>>> =
            listOf(flow { awaitCancellation() }, emptyFlow())
        for (members in neverRead) {
          val unread =
              object : CareCircleRepository by circle {
                override fun observeMembers(ownerUid: String) = members
              }
          val viewModel =
              CareCircleMemberFormViewModel(invites, profiles, auth, unread, clock = clock)
          viewModel.fill()
          val invitesBefore = invites.created.size

          viewModel.save()
          advanceTimeBy(2_999)
          runCurrent()
          assertEquals(SavePhase.SAVING, viewModel.uiState.value.save)
          assertEquals(invitesBefore, invites.created.size)

          advanceTimeBy(1)
          runCurrent()
          assertEquals(SavePhase.SAVED, viewModel.uiState.value.save)
          assertEquals(invitesBefore + 1, invites.created.size)
        }
      }

  @Test
  fun someoneNew_isInvitedAsBefore() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor("paul@example.com")))
        val viewModel = loadedViewModel()
        viewModel.fill()
        runCurrent()

        assertNull(viewModel.uiState.value.duplicate)
        saved(viewModel)

        assertEquals("julie@example.com", invites.created.single().email)
        assertEquals(SavePhase.SAVED, viewModel.uiState.value.save)
      }

  @Test
  fun changingTheEmail_liftsTheRefusal() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor("paul@example.com")))
        val viewModel = loadedViewModel()
        viewModel.fill()
        typeEmail(viewModel, "paul@example.com")
        assertFalse(viewModel.uiState.value.canSave)

        typeEmail(viewModel, "paul.other@example.com")

        assertNull(viewModel.uiState.value.duplicate)
        assertTrue(viewModel.uiState.value.canSave)
      }

  @Test
  fun aNewEmail_dropsTheOldWarningAtOnce_butTheSameEmailKeepsIt() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor("paul@example.com")))
        val viewModel = loadedViewModel()
        typeEmail(viewModel, "paul@example.com")
        val warning = EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL")
        assertEquals(warning, viewModel.uiState.value.duplicate)

        // E.g. typing past the length limit
        typeEmail(viewModel, "paul@example.com")
        assertEquals(warning, viewModel.uiState.value.duplicate)

        // Before the form has compared the new email with the circle
        viewModel.onEmailChange("paul@example.co")
        assertNull(viewModel.uiState.value.duplicate)
      }

  @Test
  fun theCircleChanging_updatesTheRefusal_whileTheFormIsOpen() =
      runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.fill()
        typeEmail(viewModel, "paul@example.com")
        assertTrue(viewModel.uiState.value.canSave)

        invites.setInvites(listOf(inviteFor("paul@example.com")))
        runCurrent()
        assertEquals(
            EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )
        assertFalse(viewModel.uiState.value.canSave)

        invites.setInvites(emptyList())
        runCurrent()
        assertNull(viewModel.uiState.value.duplicate)
        assertTrue(viewModel.uiState.value.canSave)
      }

  @Test
  fun anInvitationExpiringWhileTheFormIsOpen_isShownExpired() =
      runTest(dispatcher) {
        val untilExpiry = Duration.ofHours(1)
        val createdAt = now.minus(CareCircleInvite.VALIDITY).plus(untilExpiry)
        invites.setInvites(listOf(inviteFor("paul@example.com", createdAt = createdAt)))
        val viewModel = loadedViewModel()
        typeEmail(viewModel, "paul@example.com")
        assertEquals(
            EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )

        // The timer wakes up just after the expiry
        advanceTimeBy(untilExpiry.toMillis() + 1)
        runCurrent()
        assertEquals(
            EmailAlreadyInCircle.ExpiredInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )
      }

  @Test
  fun refreshExpiry_showsAnInvitationThatExpiredWhileThePhoneSlept() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor("paul@example.com")))
        val viewModel = loadedViewModel()
        typeEmail(viewModel, "paul@example.com")

        // The phone slept past the expiry: no timer ran
        clockJump = CareCircleInvite.VALIDITY
        assertEquals(
            EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )

        viewModel.refreshExpiry()
        runCurrent()

        assertEquals(
            EmailAlreadyInCircle.ExpiredInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )
      }

  @Test
  fun anUnreadableList_doesntStopTheInvitation_norTheCheckAgainstTheOtherOne() =
      runTest(dispatcher) {
        // Neither list can be read: the patient can still invite
        circle.observeError = IllegalStateException("denied")
        invites.observeError = IllegalStateException("denied")
        val unreadable = loadedViewModel()
        unreadable.fill()
        saved(unreadable)
        assertEquals(SavePhase.SAVED, unreadable.uiState.value.save)

        // Only the members can't be read: the invitations are still checked
        invites.observeError = null
        invites.setInvites(listOf(inviteFor("paul@example.com")))
        val viewModel = loadedViewModel()
        typeEmail(viewModel, "paul@example.com")
        assertEquals(
            EmailAlreadyInCircle.PendingInvite("Paul", "INVITEPAUL"),
            viewModel.uiState.value.duplicate,
        )
      }

  @Test
  fun theInvitationJustSaved_isNotTakenForADuplicate() =
      runTest(dispatcher) {
        val viewModel = loadedViewModel()
        viewModel.fill()

        saved(viewModel)

        assertNull(viewModel.uiState.value.duplicate)
        viewModel.consumeSaved()
        // Back from the repository after the screen left: nothing should change on the way out
        invites.setInvites(invites.created.map { it.copy(createdAt = now) })
        runCurrent()
        assertNull(viewModel.uiState.value.duplicate)
      }

  @Test
  fun aSaveFailing_checksAgainTheCircleThatChangedMeanwhile() =
      runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        invites.createGate = gate
        invites.createError = IllegalStateException("boom")
        val viewModel = loadedViewModel()
        viewModel.fill()
        viewModel.save()
        runCurrent()

        invites.setInvites(
            listOf(inviteFor("julie@example.com", firstName = "Julie", inviteCode = "INVITEJULI"))
        )
        runCurrent()
        assertNull(viewModel.uiState.value.duplicate)

        gate.complete(Unit)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.saveFailed)
        assertEquals(EmailAlreadyInCircle.PendingInvite("Julie", "INVITEJULI"), state.duplicate)
        assertFalse(state.canSave)
      }

  @Test
  fun nobodySignedIn_readsNoCircle() =
      runTest(dispatcher) {
        viewModel(authRepository = FakeAuthRepository())
        advanceUntilIdle()
        assertTrue(circle.observedOwners.isEmpty())
      }

  // ---- editing a member ----

  private val marc =
      CareCircleMember(
          uid = "marc",
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
          email = "marc.dubois@email.com",
      )
  private val circle = FakeCareCircleRepository().apply { setMembers(ownerUid, listOf(marc)) }

  @Test
  fun edit_neverComparesTheEmail() =
      runTest(dispatcher) {
        invites.setInvites(listOf(inviteFor(marc.email)))
        val viewModel = editViewModel()
        advanceUntilIdle()
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.duplicate)
        assertTrue(viewModel.uiState.value.canSave)
        // Only the member being edited is read, not the whole circle
        assertEquals(listOf(ownerUid), circle.observedOwners)
      }

  private fun editViewModel(memberUid: String = marc.uid, authRepository: AuthRepository = auth) =
      CareCircleMemberFormViewModel(invites, profiles, authRepository, circle, memberUid)

  @Test
  fun edit_isLoadingAtFirst_thenFilledInWithTheMember() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        assertTrue(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isEditing)
        assertFalse(viewModel.uiState.value.canEdit)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Marc", state.firstName)
        assertEquals("Dubois", state.familyName)
        assertEquals(Relationship.SON, state.relationship)
        assertEquals("marc.dubois@email.com", state.email)
        assertEquals(marc.permissions, state.permissions)
        assertFalse(state.firstNameTouched)
        // Nothing changed yet
        assertTrue(state.unchanged)
        assertFalse(state.canSave)
      }

  @Test
  fun edit_memberWhoAddsSymptoms_keepsItWhenSavingOtherChanges() =
      runTest(dispatcher) {
        val sophie =
            marc.copy(
                uid = "sophie",
                permissions = setOf(CarePermission.SYMPTOMS),
                canAdd = setOf(CarePermission.SYMPTOMS),
            )
        circle.setMembers(ownerUid, listOf(marc, sophie))
        val viewModel = editViewModel(sophie.uid)
        advanceUntilIdle()
        assertEquals(setOf(CarePermission.SYMPTOMS), viewModel.uiState.value.canAdd)
        assertTrue(viewModel.uiState.value.unchanged)

        viewModel.onFirstNameChange("Sophie")
        saved(viewModel)

        assertEquals(setOf(CarePermission.SYMPTOMS), circle.updated.single().second.canAdd)
      }

  @Test
  fun edit_saveIsEnabledOnlyWhileSomethingDiffersFromTheMember() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        advanceUntilIdle()
        viewModel.save()
        advanceUntilIdle()
        assertTrue(circle.updated.isEmpty())

        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
        assertTrue(viewModel.uiState.value.canSave)
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, false)
        assertFalse(viewModel.uiState.value.canSave)

        viewModel.onFirstNameChange("Marco")
        assertTrue(viewModel.uiState.value.canSave)
        // Back to the same name, spaces aside: saving would store the same details
        viewModel.onFirstNameChange(" Marc ")
        assertFalse(viewModel.uiState.value.canSave)

        viewModel.onRelationshipChange(Relationship.FRIEND)
        assertTrue(viewModel.uiState.value.canSave)
        viewModel.onRelationshipChange(Relationship.SON)
        viewModel.onFamilyNameChange("")
        assertTrue(viewModel.uiState.value.canSave)
      }

  @Test
  fun edit_memberWithBlankLastName_isUnchangedOnceFilledIn() =
      runTest(dispatcher) {
        circle.setMembers(ownerUid, listOf(marc.copy(familyName = " ")))
        val viewModel = editViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.unchanged)
        assertFalse(viewModel.uiState.value.canSave)
      }

  @Test
  fun edit_memberWithoutLastNameOrEmail_isFilledInWithEmptyFields() =
      runTest(dispatcher) {
        circle.setMembers(ownerUid, listOf(marc.copy(familyName = null, email = null)))
        val viewModel = editViewModel()
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.familyName)
        assertEquals("", viewModel.uiState.value.email)
      }

  @Test
  fun edit_laterChangesToTheMember_keepThePatientsEdits() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        advanceUntilIdle()
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)

        circle.setMembers(
            ownerUid,
            listOf(marc.copy(firstName = "Marco", permissions = emptySet())),
        )
        advanceUntilIdle()

        assertEquals("Marc", viewModel.uiState.value.firstName)
        assertEquals(
            setOf(CarePermission.PLANNING, CarePermission.EVENTS, CarePermission.SYMPTOMS),
            viewModel.uiState.value.permissions,
        )
      }

  @Test
  fun edit_save_updatesTheMemberWithTrimmedDetails_withoutAnInvite() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        advanceUntilIdle()
        viewModel.onFirstNameChange(" Marco  ")
        viewModel.onFamilyNameChange("   ")
        viewModel.onRelationshipChange(Relationship.FRIEND)
        viewModel.onPermissionChange(CarePermission.EVENTS, false)

        saved(viewModel)

        assertEquals(
            "marc" to
                CareCircleMemberDetails(
                    firstName = "Marco",
                    familyName = null,
                    relationship = Relationship.FRIEND,
                    permissions = setOf(CarePermission.PLANNING),
                ),
            circle.updated.single(),
        )
        assertTrue(invites.created.isEmpty())
        val state = viewModel.uiState.value
        assertEquals(SavePhase.SAVED, state.save)
        assertNull(state.savedInviteCode)
        // The saved (trimmed) member coming back doesn't change the form that is leaving
        assertEquals(" Marco  ", state.firstName)
      }

  @Test
  fun edit_theAccountEmail_isNeverInvalid() =
      runTest(dispatcher) {
        circle.setMembers(ownerUid, listOf(marc.copy(email = "not an email")))
        val viewModel = editViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.invalidEmail)
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)
        assertTrue(viewModel.uiState.value.canSave)
      }

  @Test
  fun edit_memberNotInTheCircle_isNotFound() =
      runTest(dispatcher) {
        val viewModel = editViewModel(memberUid = "someone")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state.notFound)
        assertFalse(state.isLoading)
        assertFalse(state.showsForm)
        assertFalse(state.canSave)
      }

  @Test
  fun edit_memberRemovedWhileEditing_isNotFound() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        advanceUntilIdle()
        circle.setMembers(ownerUid, emptyList())
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.notFound)
      }

  @Test
  fun edit_unreadableMember_nobodySignedIn_orNoMember_isALoadError() =
      runTest(dispatcher) {
        val cases =
            listOf(
                { editViewModel(memberUid = " ") },
                { editViewModel(authRepository = FakeAuthRepository()) },
                {
                  circle.observeError = IllegalStateException("denied")
                  editViewModel()
                },
            )
        cases.forEach { create ->
          val viewModel = create()
          advanceUntilIdle()
          assertTrue(viewModel.uiState.value.loadFailed)
          assertFalse(viewModel.uiState.value.isLoading)
          assertFalse(viewModel.uiState.value.canEdit)
        }
        assertTrue(circle.updated.isEmpty())
      }

  @Test
  fun edit_fieldsCantChangeWhileLoading() =
      runTest(dispatcher) {
        val viewModel = editViewModel()
        viewModel.onFirstNameChange("Bruno")
        viewModel.save()
        advanceUntilIdle()
        assertEquals("Marc", viewModel.uiState.value.firstName)
        assertTrue(circle.updated.isEmpty())
      }

  @Test
  fun edit_saveError_keepsTheDraft() =
      runTest(dispatcher) {
        circle.updateError = IllegalStateException("boom")
        val viewModel = editViewModel()
        advanceUntilIdle()
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)

        saved(viewModel)

        val state = viewModel.uiState.value
        assertTrue(state.saveFailed)
        assertNotEquals(SavePhase.SAVED, state.save)
        assertTrue(CarePermission.SYMPTOMS in state.permissions)
        assertTrue(state.canSave)
      }
}
