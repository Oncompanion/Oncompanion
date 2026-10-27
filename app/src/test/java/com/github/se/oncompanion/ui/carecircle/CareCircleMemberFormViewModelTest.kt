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
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
      CareCircleMemberFormViewModel(invites, profiles, authRepository)

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
  fun save_createsTheInvite_withThePatientsFirstName_trimmedDetailsAndEmail() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.onFirstNameChange("  Julie ")
        viewModel.onFamilyNameChange(" Martin  ")
        viewModel.onRelationshipChange(Relationship.DAUGHTER)
        viewModel.onEmailChange(" julie@example.com ")
        viewModel.onPermissionChange(CarePermission.PLANNING, true)
        viewModel.onPermissionChange(CarePermission.SYMPTOMS, true)

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
            ),
            invite.details,
        )
        assertEquals("julie@example.com", invite.email)
        val state = viewModel.uiState.value
        assertTrue(state.isSaved)
        assertEquals("K7M2PQ9XRT", state.savedInviteCode)
        assertFalse(state.isSaving)
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
        assertFalse(viewModel.uiState.value.isSaving)
      }

  @Test
  fun doubleTap_createsOneInvite() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.fill()
        viewModel.save()
        assertTrue(viewModel.uiState.value.isSaving)
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
        assertFalse(state.isSaving)
        assertFalse(state.isSaved)
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
        assertTrue(viewModel.uiState.value.isSaved)
      }

  @Test
  fun missingProfile_isASaveError() =
      runTest(dispatcher) {
        val viewModel = CareCircleMemberFormViewModel(invites, FakeUserProfileRepository(), auth)
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
        val viewModel = CareCircleMemberFormViewModel(invites, cachedOnly, auth)
        viewModel.fill()
        saved(viewModel)
        assertEquals("Annie", invites.created.single().ownerFirstName)
      }

  @Test
  fun consumeSaved_clearsTheSavedState() =
      runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.fill()
        saved(viewModel)

        viewModel.consumeSaved()

        assertFalse(viewModel.uiState.value.isSaved)
        assertNull(viewModel.uiState.value.savedInviteCode)
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
        assertTrue(state.canSave)
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
        assertTrue(state.isSaved)
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
        assertFalse(state.isSaved)
        assertTrue(CarePermission.SYMPTOMS in state.permissions)
        assertTrue(state.canSave)
      }
}
