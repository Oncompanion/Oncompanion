// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.EmailAlreadyInCircle
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsAboveKeyboard
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.showKeyboard
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class CareCircleMemberFormScreenTest {

  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val complete =
      CareCircleMemberFormUiState(
          firstName = "Julie",
          familyName = "Martin",
          relationship = Relationship.DAUGHTER,
          email = "julie@example.com",
          firstNameTouched = true,
      )

  // ---- stateless content ----

  private var state by mutableStateOf(CareCircleMemberFormUiState())
  private var saves = 0
  private var backs = 0
  private val openedInvites = mutableListOf<String>()
  private val permissionChanges = mutableListOf<Pair<CarePermission, Boolean>>()

  /** Shows the content; every change updates [state] like the ViewModel would. */
  private fun content(initial: CareCircleMemberFormUiState = CareCircleMemberFormUiState()) {
    state = initial
    compose.setContent {
      CareCircleMemberFormContent(
          uiState = state,
          onFirstNameChange = { state = state.copy(firstName = it, firstNameTouched = true) },
          onFamilyNameChange = { state = state.copy(familyName = it) },
          onRelationshipChange = { state = state.copy(relationship = it) },
          onEmailChange = { state = state.copy(email = it) },
          onPermissionChange = { permission, granted ->
            permissionChanges += permission to granted
            state =
                state.copy(
                    permissions =
                        if (granted) state.permissions + permission
                        else state.permissions - permission
                )
          },
          onSave = { saves++ },
          onBack = { backs++ },
          onCanAddChange = { section, allowed ->
            state =
                state.copy(canAdd = if (allowed) state.canAdd + section else state.canAdd - section)
          },
          onOpenInvite = { openedInvites += it },
      )
    }
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  private val isError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  @Test
  fun everyField_isShown_withTheTitleAndABackButton() {
    content()
    compose.onNodeWithText(string(R.string.care_circle_form_add_title)).assertIsDisplayed()
    node(C.Tag.care_circle_form_back_button).assertIsDisplayed()
    node(C.Tag.care_circle_form_first_name).assertIsDisplayed()
    node(C.Tag.care_circle_form_family_name).assertIsDisplayed()
    node(C.Tag.care_circle_form_relationship).assertIsDisplayed()
    node(C.Tag.care_circle_form_email).performScrollTo().assertIsDisplayed()
    CarePermission.entries.forEach {
      node(C.Tag.careCircleFormPermission(it.name)).performScrollTo().assertIsDisplayed()
    }
    node(C.Tag.care_circle_form_save_button)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_send_invitation))
  }

  @Test
  fun newForm_hasEveryAccessOff_andSaveDisabled_namingBothMissingFields() {
    content()
    CarePermission.entries.forEach {
      node(C.Tag.careCircleFormPermission(it.name)).performScrollTo().assertIsOff()
    }
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    node(C.Tag.care_circle_form_missing)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_missing_both))
  }

  @Test
  fun requiredFields_sayTheyAreRequired() {
    content()
    node(C.Tag.care_circle_form_first_name)
        .assertTextContains(string(R.string.care_circle_form_required))
    node(C.Tag.care_circle_form_relationship)
        .assertTextContains(string(R.string.care_circle_form_required))
  }

  @Test
  fun missingText_namesOnlyTheFieldStillMissing() {
    content(CareCircleMemberFormUiState(firstName = "Julie"))
    node(C.Tag.care_circle_form_missing)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_missing_relationship))
    node(C.Tag.care_circle_form_save_button).assertIsNotEnabled()

    state = CareCircleMemberFormUiState(relationship = Relationship.FRIEND)
    node(C.Tag.care_circle_form_missing)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_missing_first_name))
    node(C.Tag.care_circle_form_save_button).assertIsNotEnabled()
  }

  @Test
  fun completeForm_enablesSave_hidesTheMissingText_andSaves() {
    content(complete)
    node(C.Tag.care_circle_form_missing).assertDoesNotExist()
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(1, saves) }
  }

  @Test
  fun typingAndPicking_fillsTheForm_untilItCanBeSaved() {
    content()
    node(C.Tag.care_circle_form_first_name).performTextInput("Julie")
    node(C.Tag.care_circle_form_family_name).performTextInput("Martin")
    node(C.Tag.care_circle_form_relationship).performClick()
    node(C.Tag.careCircleFormRelationshipOption(Relationship.HOME_NURSE.name))
        .performScrollTo()
        .performClick()
    node(C.Tag.care_circle_form_email).performScrollTo().performTextInput("julie@example.com")

    compose.runOnIdle {
      assertEquals("Julie", state.firstName)
      assertEquals("Martin", state.familyName)
      assertEquals(Relationship.HOME_NURSE, state.relationship)
      assertEquals("julie@example.com", state.email)
    }
    node(C.Tag.care_circle_form_relationship)
        .assertTextContains(string(R.string.relationship_home_nurse))
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsEnabled()
  }

  @Test
  fun relationshipMenu_listsEveryRelationship() {
    content()
    node(C.Tag.care_circle_form_relationship).performClick()
    Relationship.entries.forEach {
      node(C.Tag.careCircleFormRelationshipOption(it.name)).performScrollTo().assertIsDisplayed()
    }
  }

  @Test
  fun switches_toggleTheirAccess() {
    content()
    val symptoms = node(C.Tag.careCircleFormPermission(CarePermission.SYMPTOMS.name))
    symptoms.performScrollTo().performClick()
    symptoms.assertIsOn()
    symptoms.performClick()
    symptoms.assertIsOff()
    compose.runOnIdle {
      assertEquals(
          listOf(CarePermission.SYMPTOMS to true, CarePermission.SYMPTOMS to false),
          permissionChanges,
      )
    }
  }

  @Test
  fun switchRows_showWhatEachSectionShows() {
    content()
    CarePermission.entries.forEach {
      node(C.Tag.careCircleFormPermission(it.name))
          .performScrollTo()
          .assertTextContains(string(it.label))
          .assertTextContains(string(it.description))
    }
  }

  @Test
  fun accessTitle_usesTheFirstName_orTheyWhileBlank() {
    content()
    node(C.Tag.care_circle_form_can_see)
        .assertTextEquals(string(R.string.care_circle_form_can_see_unnamed))
    state = state.copy(firstName = " Julie ")
    node(C.Tag.care_circle_form_can_see)
        .assertTextEquals(string(R.string.care_circle_form_can_see, "Julie"))
  }

  @Test
  fun untouchedFirstName_isNotAnError_butAClearedOneIs() {
    content()
    node(C.Tag.care_circle_form_first_name).assert(isError.not())
    state = state.copy(firstNameTouched = true)
    node(C.Tag.care_circle_form_first_name).assert(isError)
  }

  @Test
  fun invalidEmail_saysSo_andDisablesSave() {
    content(complete.copy(email = "julie@"))
    node(C.Tag.care_circle_form_email)
        .performScrollTo()
        .assertTextContains(string(R.string.care_circle_form_invalid_email))
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    // The email has its own message: nothing required is missing
    node(C.Tag.care_circle_form_missing).assertDoesNotExist()
  }

  @Test
  fun validEmail_explainsWhatItIsFor() {
    content(complete)
    node(C.Tag.care_circle_form_email)
        .performScrollTo()
        .assertTextContains(string(R.string.care_circle_form_email_hint))
  }

  @Test
  fun saveError_isShown() {
    content(complete.copy(saveFailed = true))
    node(C.Tag.care_circle_form_save_error)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_save_error))
    node(C.Tag.care_circle_form_save_button).assertIsEnabled()
  }

  @Test
  fun saving_showsProgress_andLocksTheForm() {
    content(complete.copy(save = SavePhase.SAVING))
    node(C.Tag.care_circle_form_saving).performScrollTo().assertIsDisplayed()
    node(C.Tag.care_circle_form_save_button).assertIsNotEnabled()
    node(C.Tag.care_circle_form_first_name).assertIsNotEnabled()
    node(C.Tag.care_circle_form_email).assertIsNotEnabled()
    node(C.Tag.careCircleFormPermission(CarePermission.PLANNING.name))
        .performScrollTo()
        .assertIsNotEnabled()
  }

  @Test
  fun saved_locksTheForm_withoutProgress() {
    content(complete.copy(save = SavePhase.SAVED))
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    node(C.Tag.care_circle_form_saving).assertDoesNotExist()
    node(C.Tag.care_circle_form_first_name).assertIsNotEnabled()
  }

  @Test
  fun backArrow_goesBack() {
    content()
    node(C.Tag.care_circle_form_back_button).performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  @Test
  fun keyboardOpen_sendCanBeScrolledAboveIt() {
    content(complete)
    val keyboardHeight = 600
    compose.showKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeKeyboardOpen_keepsTheTypedFieldAndTheWayBackReachable() {
    content(complete)
    val keyboardHeight = 500

    // Focus without a tap: a tap can open the text handle, a window of its own
    node(C.Tag.care_circle_form_email).performScrollTo().requestFocus()
    compose.showKeyboard(keyboardHeight)

    node(C.Tag.care_circle_form_email).performScrollTo().assertIsFullyVisible()
    node(C.Tag.care_circle_form_email).assertIsAboveKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_back_button).performScrollTo().assertIsFullyVisible()
  }

  // ---- inviting someone already in the circle (#257) ----

  private val expiredInvite =
      CareCircleInvite(
          inviteCode = "OLDCODE234",
          ownerUid = "owner",
          ownerFirstName = "Anne",
          details = CareCircleMemberDetails("Julie", relationship = Relationship.DAUGHTER),
          email = "julie@example.com",
          createdAt = Instant.EPOCH,
      )

  @Test
  fun duplicate_explainsWhy_refusesToSend_andOpensTheInvitationIfAny() {
    val pending = EmailAlreadyInCircle.PendingInvite("Julie", "NEWCODE234")
    val expired = EmailAlreadyInCircle.ExpiredInvite("Julie", "OLDCODE234")
    val memberText = string(R.string.care_circle_form_duplicate, "Julie")
    val pendingText = string(R.string.care_circle_form_duplicate_pending, "Julie")
    val expiredText = string(R.string.care_circle_form_duplicate_expired, "Julie")
    val ownEmail = complete.copy(accountEmail = complete.email)
    val ownText = string(R.string.care_circle_form_own_email)
    // The form, its warning, and the invite code the link opens (none for a member)
    val cases =
        listOf(
            Triple(
                complete.copy(duplicate = EmailAlreadyInCircle.Member("Julie")),
                memberText,
                null,
            ),
            Triple(complete.copy(duplicate = pending), pendingText, "NEWCODE234"),
            Triple(complete.copy(duplicate = expired), expiredText, "OLDCODE234"),
            // Filling in the rest wouldn't allow sending: the form doesn't ask for it
            Triple(CareCircleMemberFormUiState(duplicate = pending), pendingText, "NEWCODE234"),
            Triple(ownEmail, ownText, null),
            // Filling in the rest wouldn't allow sending: the form doesn't ask for it
            Triple(
                CareCircleMemberFormUiState(email = complete.email, accountEmail = complete.email),
                ownText,
                null,
            ),
            // An invitation already sent to their own email: nothing there to open
            Triple(ownEmail.copy(duplicate = pending), ownText, null),
        )
    content(cases.first().first)
    for ((form, warning, inviteCode) in cases) {
      state = form
      node(C.Tag.care_circle_form_email)
          .performScrollTo()
          .assert(isError)
          .assertTextContains(warning)
      node(C.Tag.care_circle_form_save_button)
          .performScrollTo()
          .assertIsNotEnabled()
          .assertTextEquals(string(R.string.care_circle_form_send_invitation))
      node(C.Tag.care_circle_form_missing).assertDoesNotExist()
      if (inviteCode == null) {
        node(C.Tag.care_circle_form_open_invite).assertDoesNotExist()
      } else {
        node(C.Tag.care_circle_form_open_invite)
            .performScrollTo()
            .assertTextEquals(string(R.string.care_circle_form_open_invite))
            .performClick()
        compose.runOnIdle { assertEquals(inviteCode, openedInvites.last()) }
      }
    }
    compose.runOnIdle { assertEquals(0, saves) }
  }

  @Test
  fun duplicate_whileSaving_hidesTheLink() {
    content(
        complete.copy(
            duplicate = EmailAlreadyInCircle.PendingInvite("Julie", "NEWCODE234"),
            save = SavePhase.SAVING,
        )
    )
    node(C.Tag.care_circle_form_open_invite).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun duplicate_landscape_showsTheWarningAndTheLinkInFull() {
    content(complete.copy(duplicate = EmailAlreadyInCircle.ExpiredInvite("Julie", "OLDCODE234")))
    node(C.Tag.care_circle_form_email).performScrollTo().assertIsFullyVisible()
    node(C.Tag.care_circle_form_open_invite).performScrollTo().assertIsFullyVisible()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun duplicate_landscapeKeyboardOpen_keepsTheWarningAndTheLinkAboveIt() {
    content(complete.copy(duplicate = EmailAlreadyInCircle.ExpiredInvite("Julie", "OLDCODE234")))
    val keyboardHeight = 500
    node(C.Tag.care_circle_form_email).performScrollTo().requestFocus()
    compose.showKeyboard(keyboardHeight)

    node(C.Tag.care_circle_form_email).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_open_invite).performScrollTo().assertIsFullyVisible()
    node(C.Tag.care_circle_form_open_invite).assertIsAboveKeyboard(keyboardHeight)
  }

  /** The add form wired to a ViewModel, in a circle holding [invites]. */
  private fun addFormViewModel(
      invites: FakeCareCircleInviteRepository,
      members: List<CareCircleMember> = emptyList(),
  ): CareCircleMemberFormViewModel {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val circle = FakeCareCircleRepository().apply { setMembers("owner", members) }
    return CareCircleMemberFormViewModel(invites, FakeUserProfileRepository(), auth, circle)
  }

  @Test
  fun screen_typingAMembersEmail_warns_andTheWarningSurvivesRotation() {
    val members =
        listOf(CareCircleMember(uid = "marc", firstName = "Marc", email = "marc@example.com"))
    val invites = FakeCareCircleInviteRepository()
    val viewModel = addFormViewModel(invites, members)
    val show = {
      compose.activity.setContent {
        CareCircleMemberFormScreen(
            onBack = {},
            onSaved = {},
            onOpenInvite = {},
            viewModel = viewModel,
        )
      }
    }
    compose.runOnUiThread(show)

    node(C.Tag.care_circle_form_email).performScrollTo().performTextInput(" MARC@example.com")
    val warning = string(R.string.care_circle_form_duplicate, "Marc")
    node(C.Tag.care_circle_form_email).assertTextContains(warning)

    compose.activityRule.scenario.recreate()
    compose.runOnUiThread(show)

    node(C.Tag.care_circle_form_email)
        .performScrollTo()
        .assertTextContains(" MARC@example.com")
        .assertTextContains(warning)
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    compose.runOnIdle { assertTrue(invites.created.isEmpty()) }
  }

  @Test
  fun screen_anExpiredInvitation_isOpened_notRenewedByTheForm() {
    val invites = FakeCareCircleInviteRepository().apply { setInvites(listOf(expiredInvite)) }
    val viewModel = addFormViewModel(invites)
    val opened = mutableListOf<String>()
    compose.setContent {
      CareCircleMemberFormScreen(
          onBack = {},
          onSaved = {},
          onOpenInvite = { opened += it },
          viewModel = viewModel,
      )
    }

    node(C.Tag.care_circle_form_email).performScrollTo().performTextInput("julie@example.com")
    node(C.Tag.care_circle_form_email)
        .assertTextContains(string(R.string.care_circle_form_duplicate_expired, "Julie"))
    node(C.Tag.care_circle_form_open_invite).performScrollTo().performClick()

    compose.runOnIdle {
      assertEquals(listOf("OLDCODE234"), opened)
      assertEquals(expiredInvite, invites.invite(expiredInvite.inviteCode))
      assertTrue(invites.created.isEmpty())
    }
  }

  // ---- editing a member ----

  /** Marc as in the Figma "US-14 / Edit member (email read-only)". */
  private val editing =
      CareCircleMemberFormUiState(
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          email = "marc.dubois@email.com",
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
          isEditing = true,
      )

  @Test
  fun edit_showsEditMember_saveChanges_andTheAccountEmailReadOnly() {
    content(editing)
    compose.onNodeWithText(string(R.string.care_circle_form_edit_title)).assertIsDisplayed()
    compose.onNodeWithText(string(R.string.care_circle_form_add_title)).assertDoesNotExist()
    node(C.Tag.care_circle_form_email)
        .assertIsNotEnabled()
        .assertTextContains("marc.dubois@email.com")
        .assertTextContains(string(R.string.care_circle_form_account_email))
    node(C.Tag.care_circle_form_first_name).assertIsEnabled()
    node(C.Tag.care_circle_form_can_see).assertTextEquals("What can Marc see?")
    node(C.Tag.care_circle_form_save_button)
        .performScrollTo()
        .assertIsEnabled()
        .assertTextEquals(string(R.string.care_circle_form_save_changes))
        .performClick()
    node(C.Tag.care_circle_form_missing).assertDoesNotExist()
    compose.runOnIdle { assertEquals(1, saves) }
  }

  @Test
  fun edit_unchanged_disablesSaveChanges_withoutAMissingText() {
    content(editing.copy(loadedDetails = editing.details))
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    node(C.Tag.care_circle_form_missing).assertDoesNotExist()
  }

  @Test
  fun edit_memberWithoutEmail_hidesTheEmail() {
    content(editing.copy(email = ""))
    node(C.Tag.care_circle_form_first_name).assertIsDisplayed()
    node(C.Tag.care_circle_form_email).assertDoesNotExist()
  }

  @Test
  fun edit_blankFirstName_saysItIsNeededToSave() {
    content(editing.copy(firstName = "", firstNameTouched = true))
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsNotEnabled()
    node(C.Tag.care_circle_form_missing)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_missing_first_name_edit))
  }

  @Test
  fun edit_saveError_saysTheChangesWerentSaved() {
    content(editing.copy(saveFailed = true))
    node(C.Tag.care_circle_form_save_error)
        .performScrollTo()
        .assertTextEquals(string(R.string.care_circle_form_update_error))
  }

  @Test
  fun edit_loadingNotFoundAndLoadError_replaceTheForm_butKeepTheWayBack() {
    content(editing.copy(isLoading = true))
    val states =
        listOf(
            editing.copy(isLoading = true) to C.Tag.care_circle_form_loading,
            editing.copy(notFound = true) to C.Tag.care_circle_form_not_found,
            editing.copy(loadFailed = true) to C.Tag.care_circle_form_load_error,
        )
    states.forEach { (uiState, tag) ->
      compose.runOnIdle { state = uiState }
      node(tag).assertIsDisplayed()
      node(C.Tag.care_circle_form_first_name).assertDoesNotExist()
      node(C.Tag.care_circle_form_save_button).assertDoesNotExist()
      compose.onNodeWithText(string(R.string.care_circle_form_edit_title)).assertIsDisplayed()
      node(C.Tag.care_circle_form_back_button).performClick()
    }
    compose.runOnIdle { assertEquals(states.size, backs) }
    compose.onNodeWithText(string(R.string.care_circle_member_error)).assertIsDisplayed()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun edit_landscapeKeyboardOpen_keepsTheTypedFieldAndSaveReachable() {
    content(editing)
    val keyboardHeight = 500
    node(C.Tag.care_circle_form_first_name).requestFocus()
    compose.showKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_first_name).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_save_button).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
    node(C.Tag.care_circle_form_back_button).performScrollTo().assertIsFullyVisible()
  }

  @Test
  fun previews_render() {
    compose.setContent { CareCircleMemberFormEditPreview() }
    compose.onNodeWithText(string(R.string.care_circle_form_save_changes)).assertExists()
  }

  @Test
  fun addPreview_renders() {
    compose.setContent { CareCircleMemberFormScreenPreview() }
    node(C.Tag.care_circle_form_can_see).assertTextEquals("What can Julie see?")
  }

  // ---- screen wired to its ViewModel ----

  @Test
  fun screen_editingAMember_savesTheChangesThroughTheViewModel_withoutAnInviteCode() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val circle =
        FakeCareCircleRepository().apply {
          setMembers(
              "owner",
              listOf(
                  CareCircleMember(
                      uid = "marc",
                      firstName = "Marc",
                      relationship = Relationship.SON,
                      email = "marc.dubois@email.com",
                  )
              ),
          )
        }
    val invites = FakeCareCircleInviteRepository()
    val viewModel =
        CareCircleMemberFormViewModel(
            invites,
            FakeUserProfileRepository(),
            auth,
            circle,
            memberUid = "marc",
        )
    val savedCodes = mutableListOf<String?>()
    compose.setContent {
      CareCircleMemberFormScreen(
          onBack = {},
          onSaved = { savedCodes += it },
          onOpenInvite = {},
          viewModel = viewModel,
      )
    }

    node(C.Tag.care_circle_form_email).assertTextContains("marc.dubois@email.com")
    node(C.Tag.careCircleFormPermission(CarePermission.SYMPTOMS.name))
        .performScrollTo()
        .performClick()
    node(C.Tag.care_circle_form_save_button).performScrollTo().performClick()

    compose.runOnIdle {
      assertEquals(listOf<String?>(null), savedCodes)
      assertEquals(setOf(CarePermission.SYMPTOMS), circle.updated.single().second.permissions)
      assertTrue(invites.created.isEmpty())
    }
  }

  @Test
  fun screen_savesTheInviteThroughTheViewModel_andReportsItsInviteCode() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    val invites = FakeCareCircleInviteRepository().apply { nextInviteCode = { "K7M2PQ9XRT" } }
    val profiles =
        FakeUserProfileRepository().apply {
          seed(UserProfile(uid = "owner", role = Role.PATIENT, firstName = "Anne"))
        }
    val viewModel =
        CareCircleMemberFormViewModel(invites, profiles, auth, FakeCareCircleRepository())
    val savedCodes = mutableListOf<String?>()
    compose.setContent {
      CareCircleMemberFormScreen(
          onBack = { backs++ },
          onSaved = { savedCodes += it },
          onOpenInvite = {},
          viewModel = viewModel,
      )
    }

    node(C.Tag.care_circle_form_first_name).performTextInput("Julie")
    node(C.Tag.care_circle_form_relationship).performClick()
    node(C.Tag.careCircleFormRelationshipOption(Relationship.DAUGHTER.name)).performClick()
    node(C.Tag.careCircleFormPermission(CarePermission.PLANNING.name))
        .performScrollTo()
        .performClick()
    node(C.Tag.care_circle_form_save_button).performScrollTo().performClick()

    compose.runOnIdle {
      assertEquals(listOf<String?>("K7M2PQ9XRT"), savedCodes)
      val invite = invites.created.single()
      assertEquals("Anne", invite.ownerFirstName)
      assertEquals(
          CareCircleMemberDetails(
              firstName = "Julie",
              relationship = Relationship.DAUGHTER,
              permissions = setOf(CarePermission.PLANNING),
          ),
          invite.details,
      )
      assertTrue(invite.email == null)
    }
    node(C.Tag.care_circle_form_back_button).performClick()
    compose.runOnIdle { assertEquals(1, backs) }
  }

  // ---- adding symptoms (US-22) ----

  @Test
  fun allowAddingSymptoms_isGreyedOut_untilSymptomsAreShared() {
    content(complete)
    val canAdd = compose.onNodeWithTag(C.Tag.careCircleFormCanAdd(CarePermission.SYMPTOMS.name))

    canAdd.performScrollTo().assertIsNotEnabled().assertIsOff()

    compose
        .onNodeWithTag(C.Tag.careCircleFormPermission(CarePermission.SYMPTOMS.name))
        .performScrollTo()
        .performClick()
    canAdd.performScrollTo().assertIsEnabled().performClick()

    canAdd.assertIsOn()
    assertEquals(setOf(CarePermission.SYMPTOMS), state.canAdd)
  }

  @Test
  fun allowAdding_isOnlyOfferedUnderSymptoms() {
    content(complete)

    for (section in CarePermission.entries - CarePermission.SYMPTOMS) {
      compose.onNodeWithTag(C.Tag.careCircleFormCanAdd(section.name)).assertDoesNotExist()
    }
  }
}
