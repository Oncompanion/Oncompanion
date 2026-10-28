// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.Relationship
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CareCircleMemberScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Marc as in the "US-15 / Member detail" mockup. */
  private val marc =
      CareCircleMember(
          uid = "marc",
          firstName = "Marc",
          familyName = "Dubois",
          relationship = Relationship.SON,
          permissions = setOf(CarePermission.PLANNING, CarePermission.EVENTS),
          email = "marc.dubois@email.com",
          // Mid-month, so it is August in every time zone
          addedAt = Instant.parse("2026-08-14T09:30:00Z"),
      )

  /** Records the clicks of [CareCircleMemberContent]. */
  private class Events {
    var backClicks = 0
    var retryClicks = 0
    val edited = mutableListOf<CareCircleMember>()
    val removed = mutableListOf<CareCircleMember>()
    var confirms = 0
    var dismisses = 0
  }

  private fun setContent(uiState: CareCircleMemberUiState): Events =
      Events().also { setStatefulContent(uiState, it) }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun permissionRow(permission: CarePermission) =
      composeTestRule.onNodeWithTag(C.Tag.careCircleMemberPermission(permission.name))

  // ---- every state ----

  /** Like [setContent], with a state the test can change after the content is set. */
  private fun setStatefulContent(
      initial: CareCircleMemberUiState,
      events: Events = Events(),
  ): MutableState<CareCircleMemberUiState> {
    val state = mutableStateOf(initial)
    composeTestRule.setContent {
      CareCircleMemberContent(
          uiState = state.value,
          onBack = { events.backClicks++ },
          onEdit = { events.edited += it },
          onRemove = { events.removed += it },
          onConfirmRemove = { events.confirms++ },
          onDismissRemove = { events.dismisses++ },
          onRetry = { events.retryClicks++ },
      )
    }
    return state
  }

  @Test
  fun everyState_showsTheTitleAndABackButton() {
    val events = Events()
    val state = setStatefulContent(CareCircleMemberUiState.Loading, events)
    val states =
        listOf(
            CareCircleMemberUiState.Loading,
            CareCircleMemberUiState.NotFound,
            CareCircleMemberUiState.Error,
            CareCircleMemberUiState.Member(marc),
        )
    states.forEach { uiState ->
      composeTestRule.runOnIdle { state.value = uiState }
      composeTestRule.onNodeWithText(string(R.string.care_circle_member_title)).assertIsDisplayed()
      composeTestRule.onNodeWithTag(C.Tag.care_circle_member_back_button).performClick()
    }
    composeTestRule.runOnIdle { assertEquals(states.size, events.backClicks) }
  }

  @Test
  fun loading_showsOnlyTheProgressIndicator() {
    setContent(CareCircleMemberUiState.Loading)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_details).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_not_found).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_edit_button).assertDoesNotExist()
  }

  @Test
  fun notFound_explainsIt_withoutRetryNorActions() {
    setContent(CareCircleMemberUiState.NotFound)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_not_found).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_member_not_found))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_retry_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_details).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_edit_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).assertDoesNotExist()
  }

  @Test
  fun error_showsAMessageAndRetries() {
    val events = setContent(CareCircleMemberUiState.Error)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_error).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.care_circle_member_error)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_details).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_edit_button).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_retry_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.retryClicks) }
  }

  // ---- member ----

  @Test
  fun member_showsInitialNameAndSubtitle_likeTheMockup() {
    setContent(CareCircleMemberUiState.Member(marc))
    // The avatar doesn't merge its text, so the initial is its child
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_initial).onChild().assertTextEquals("M")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertTextEquals("Marc Dubois")
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Son · Member since Aug 2026")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_loading).assertDoesNotExist()
  }

  @Test
  fun member_justUpdated_saysSoInsteadOfWhenTheyJoined() {
    setContent(CareCircleMemberUiState.Member(marc, updatedJustNow = true))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_subtitle)
        .assertTextEquals("Son · Updated just now")
  }

  @Test
  fun previews_render() {
    composeTestRule.setContent {
      CareCircleMemberScreenPreview()
      CareCircleMemberScreenNotFoundPreview()
      CareCircleMemberScreenRemovePreview()
    }
    composeTestRule.onNodeWithText("Marc Dubois").assertExists()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dialog).assertExists()
  }

  // ---- removing ----

  @Test
  fun removeDialog_asksAboutTheMember_andReportsConfirmOrCancel() {
    val events = setContent(CareCircleMemberUiState.Member(marc, confirmingRemoval = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dialog).assertIsDisplayed()
    composeTestRule.onNodeWithText("Remove Marc from your circle?").assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.care_circle_member_remove_body))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dismiss).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_confirm).performClick()
    composeTestRule.runOnIdle {
      assertEquals(1, events.dismisses)
      assertEquals(1, events.confirms)
    }
  }

  @Test
  fun removeDialog_isClosedUntilAsked() {
    setContent(CareCircleMemberUiState.Member(marc))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_error).assertDoesNotExist()
  }

  @Test
  fun removing_disablesTheActions() {
    setContent(CareCircleMemberUiState.Member(marc, isRemoving = true))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_edit_button).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).assertIsNotEnabled()
  }

  @Test
  fun removeError_isShown_andRemoveCanBeTriedAgain() {
    setContent(CareCircleMemberUiState.Member(marc, removeFailed = true))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_remove_error)
        .assertTextEquals(string(R.string.care_circle_member_remove_error))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).assertIsEnabled()
  }

  @Test
  fun removed_showsNoDetails() {
    setContent(CareCircleMemberUiState.Removed)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_details).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_not_found).assertDoesNotExist()
  }

  @Test
  fun member_withoutAddedDate_showsOnlyTheRelationship() {
    setContent(CareCircleMemberUiState.Member(marc.copy(addedAt = null)))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_subtitle).assertTextEquals("Son")
  }

  @Test
  fun member_withEmail_showsItUnderContact() {
    setContent(CareCircleMemberUiState.Member(marc))
    composeTestRule.onNodeWithText(string(R.string.care_circle_member_contact)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_email).assertIsDisplayed()
    composeTestRule.onNodeWithText("marc.dubois@email.com").assertIsDisplayed()
  }

  @Test
  fun member_withoutEmail_hidesContact() {
    val state = setStatefulContent(CareCircleMemberUiState.Member(marc.copy(email = null)))
    listOf(null, " ").forEach { email ->
      composeTestRule.runOnIdle {
        state.value = CareCircleMemberUiState.Member(marc.copy(email = email))
      }
      composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertIsDisplayed()
      composeTestRule.onNodeWithTag(C.Tag.care_circle_member_email).assertDoesNotExist()
      composeTestRule
          .onNodeWithText(string(R.string.care_circle_member_contact))
          .assertDoesNotExist()
    }
  }

  @Test
  fun member_permissionSwitches_matchWhatTheMemberCanSee() {
    setContent(CareCircleMemberUiState.Member(marc))
    composeTestRule.onNodeWithText(string(R.string.care_circle_member_can_see)).assertIsDisplayed()
    permissionRow(CarePermission.PLANNING).assertIsOn()
    permissionRow(CarePermission.EVENTS).assertIsOn()
    permissionRow(CarePermission.SYMPTOMS).performScrollTo().assertIsOff()
    permissionRow(CarePermission.PRESCRIPTIONS).performScrollTo().assertIsOff()
  }

  @Test
  fun member_everyPermissionShowsItsNameAndDescription() {
    setContent(CareCircleMemberUiState.Member(marc))
    CarePermission.entries.forEach { permission ->
      permissionRow(permission).performScrollTo().assertIsDisplayed()
      composeTestRule.onNodeWithText(string(permission.label)).assertExists()
      composeTestRule.onNodeWithText(string(permission.description)).assertExists()
    }
    composeTestRule.onNodeWithText("Treatments & appointments").assertExists()
  }

  @Test
  fun member_permissionSwitches_areReadOnly() {
    setContent(CareCircleMemberUiState.Member(marc))
    CarePermission.entries.forEach { permission ->
      permissionRow(permission).performScrollTo().assertHasNoClickAction()
    }
    permissionRow(CarePermission.PLANNING).performClick().assertIsOn()
    permissionRow(CarePermission.SYMPTOMS).performClick().assertIsOff()
  }

  @Test
  fun member_editAndRemove_reportTheMember() {
    val events = setContent(CareCircleMemberUiState.Member(marc))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_edit_button)
        .assertIsDisplayed()
        .assertHasClickAction()
        .performClick()
    composeTestRule.onNodeWithText(string(R.string.care_circle_member_remove)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf(marc), events.edited)
      assertEquals(listOf(marc), events.removed)
    }
  }

  @Test
  fun everyPermission_hasADescription() {
    CarePermission.entries.forEach { assertTrue(context.getString(it.description).isNotBlank()) }
    assertEquals("Your symptom log", context.getString(CarePermission.SYMPTOMS.description))
  }

  // ---- screen wired to its ViewModel ----

  /** Records navigation instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    var backCalls = 0
    val opened = mutableListOf<String>()

    override fun goBack() {
      backCalls++
    }

    override fun navigateTo(screen: String) {
      opened += screen
    }
  }

  private val repository = FakeCareCircleRepository()

  private fun setScreen(): RecordingNavigationActions {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    repository.setMembers("owner", listOf(marc))
    val viewModel =
        CareCircleMemberViewModel(
            SavedStateHandle(mapOf(Screen.CARE_CIRCLE_MEMBER_ARG to marc.uid)),
            repository,
            auth,
        )
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      CareCircleMemberScreen(navigationActions = navigationActions, viewModel = viewModel)
    }
    return navigationActions
  }

  @Test
  fun screen_showsTheViewModelsMember_andBackGoesBack() {
    val navigationActions = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_name).assertTextEquals("Marc Dubois")
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_back_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, navigationActions.backCalls) }
  }

  @Test
  fun screen_editMember_opensTheMemberForm() {
    val navigationActions = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_edit_button).performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf("care_circle_edit_member/marc"), navigationActions.opened)
    }
  }

  @Test
  fun screen_removingTheMember_asksFirst_thenRemovesAndGoesBack() {
    val navigationActions = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dismiss).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_dialog).assertDoesNotExist()
    composeTestRule.runOnIdle { assertEquals(0, navigationActions.backCalls) }

    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_button).performClick()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_remove_confirm).performClick()
    composeTestRule.runOnIdle {
      assertEquals(listOf("marc"), repository.removed)
      assertEquals(1, navigationActions.backCalls)
    }
  }
}
