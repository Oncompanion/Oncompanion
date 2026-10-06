package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
class CareCircleScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

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
  private val laura =
      CareCircleMember(
          uid = "laura",
          firstName = "Laura",
          familyName = "Petit",
          relationship = Relationship.HOME_NURSE,
          permissions = setOf(CarePermission.SYMPTOMS, CarePermission.PLANNING),
      )

  /** Records the clicks of [CareCircleContent]. */
  private class Events {
    var backClicks = 0
    var addClicks = 0
    var retryClicks = 0
    val memberClicks = mutableListOf<CareCircleMember>()
  }

  private fun setContent(uiState: CareCircleUiState): Events {
    val events = Events()
    composeTestRule.setContent {
      CareCircleContent(
          uiState = uiState,
          onBack = { events.backClicks++ },
          onMemberClick = { events.memberClicks += it },
          onAddMember = { events.addClicks++ },
          onRetry = { events.retryClicks++ },
      )
    }
    return events
  }

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  // ---- every state ----

  @Test
  fun everyState_showsTheTitleAndABackButton() {
    val events = setContent(CareCircleUiState.Loading)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.care_circle_title)).assertIsDisplayed()
    composeTestRule
        .onNode(
            hasTestTag(C.Tag.care_circle_back_button) and
                hasContentDescription(string(R.string.care_circle_back))
        )
        .assertIsDisplayed()
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.backClicks) }
  }

  // ---- loading ----

  @Test
  fun loading_showsOnlyTheProgressIndicator() {
    setContent(CareCircleUiState.Loading)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_error).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_add_member_fab).assertDoesNotExist()
  }

  // ---- empty ----

  @Test
  fun empty_explainsTheCircleAndOffersToAddAMember() {
    val events = setContent(CareCircleUiState.Empty)
    composeTestRule.onNodeWithText(string(R.string.care_circle_intro)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.care_circle_empty_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.care_circle_empty_body)).assertIsDisplayed()
    // A single add button: the FAB is hidden while the circle is empty
    composeTestRule.onNodeWithTag(C.Tag.care_circle_add_member_fab).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_loading).assertDoesNotExist()

    composeTestRule
        .onNode(
            hasTestTag(C.Tag.care_circle_empty_add_button) and
                hasText(string(R.string.care_circle_empty_add_member))
        )
        .assertIsDisplayed()
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.addClicks) }
  }

  // ---- error ----

  @Test
  fun error_showsAMessageAndRetries() {
    val events = setContent(CareCircleUiState.Error)
    composeTestRule.onNodeWithTag(C.Tag.care_circle_error).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.care_circle_error)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_member_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_add_member_fab).assertDoesNotExist()

    composeTestRule
        .onNode(
            hasTestTag(C.Tag.care_circle_retry_button) and
                hasText(string(R.string.care_circle_retry))
        )
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.retryClicks) }
  }

  // ---- members ----

  @Test
  fun members_showsIntroCountAndEveryMember() {
    setContent(CareCircleUiState.Members(listOf(laura, marc, sophie)))
    composeTestRule.onNodeWithText(string(R.string.care_circle_intro)).assertIsDisplayed()
    composeTestRule
        .onNode(
            hasTestTag(C.Tag.care_circle_members_count) and
                hasText(string(R.string.care_circle_members_count, 3))
        )
        .assertIsDisplayed()
    listOf(laura, marc, sophie).forEach { member ->
      composeTestRule
          .onNodeWithTag(C.Tag.careCircleMember(member.uid))
          .assertIsDisplayed()
          .assertHasClickAction()
      composeTestRule.onNodeWithText(member.fullName).assertIsDisplayed()
    }
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty).assertDoesNotExist()
  }

  @Test
  fun members_showRelationshipAndAccessSummary_likeTheMockup() {
    setContent(CareCircleUiState.Members(listOf(laura, marc, sophie)))
    composeTestRule.onNodeWithText("Wife · Full access").assertIsDisplayed()
    composeTestRule.onNodeWithText("Son · Planning & events").assertIsDisplayed()
    composeTestRule.onNodeWithText("Home nurse · Planning & symptoms").assertIsDisplayed()
  }

  @Test
  fun members_showTheirInitial() {
    setContent(CareCircleUiState.Members(listOf(sophie)))
    composeTestRule.onNodeWithText("S").assertIsDisplayed()
  }

  @Test
  fun clickingAMember_reportsIt() {
    val events = setContent(CareCircleUiState.Members(listOf(marc, sophie)))
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember(sophie.uid)).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf(sophie), events.memberClicks) }
  }

  @Test
  fun members_fabAddsAMember() {
    val events = setContent(CareCircleUiState.Members(listOf(sophie)))
    // The FAB clears its label's semantics, so the label is only in the unmerged tree
    composeTestRule
        .onNode(
            hasText(string(R.string.care_circle_add_member)) and
                hasAnyAncestor(hasTestTag(C.Tag.care_circle_add_member_fab)),
            useUnmergedTree = true,
        )
        .assertExists()
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_add_member_fab)
        .assertIsDisplayed()
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, events.addClicks) }
  }

  @Test
  fun longCircle_lastMemberCanBeScrolledTo() {
    val members =
        (1..20).map { CareCircleMember(uid = "m$it", firstName = "Member", familyName = "$it") }
    setContent(CareCircleUiState.Members(members))
    composeTestRule
        .onNodeWithTag(C.Tag.care_circle_member_list)
        .performScrollToNode(hasTestTag(C.Tag.careCircleMember("m20")))
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember("m20")).assertIsDisplayed()
  }

  // ---- access summary and labels ----

  @Test
  fun accessSummary_coversEveryNumberOfPermissions() {
    val cases =
        mapOf(
            emptySet<CarePermission>() to "No access",
            setOf(CarePermission.EVENTS) to "Events",
            setOf(CarePermission.EVENTS, CarePermission.PLANNING) to "Planning & events",
            setOf(CarePermission.PRESCRIPTIONS, CarePermission.EVENTS, CarePermission.PLANNING) to
                "Planning, events & prescriptions",
            CarePermission.entries.toSet() to "Full access",
        )
    val results = mutableMapOf<Set<CarePermission>, String>()
    composeTestRule.setContent {
      cases.keys.forEach { permissions -> results[permissions] = accessSummary(permissions) }
    }
    composeTestRule.runOnIdle { assertEquals(cases, results) }
  }

  @Test
  fun everyRelationshipAndPermission_hasALabel() {
    Relationship.entries.forEach { assert(context.getString(it.label).isNotBlank()) }
    CarePermission.entries.forEach { assert(context.getString(it.label).isNotBlank()) }
    assertEquals("Home nurse", context.getString(Relationship.HOME_NURSE.label))
    assertEquals("Prescriptions", context.getString(CarePermission.PRESCRIPTIONS.label))
  }

  // ---- screen wired to its ViewModel ----

  /** Records navigation instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    var backCalls = 0

    override fun goBack() {
      backCalls++
    }
  }

  private fun setScreen(members: List<CareCircleMember>): RecordingNavigationActions {
    val repository = FakeCareCircleRepository()
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "owner") })
    runBlocking { auth.signInWithGoogle("token") }
    repository.setMembers("owner", members)
    val viewModel = CareCircleViewModel(repository, auth)
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      CareCircleScreen(navigationActions = navigationActions, viewModel = viewModel)
    }
    return navigationActions
  }

  @Test
  fun screen_showsTheViewModelsMembers_andBackGoesBack() {
    val navigationActions = setScreen(listOf(sophie))
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember(sophie.uid)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.care_circle_back_button).performClick()
    composeTestRule.runOnIdle { assertEquals(1, navigationActions.backCalls) }
  }

  @Test
  fun screen_clickingAMember_explainsTheDetailScreenIsNotImplemented() {
    setScreen(listOf(sophie))
    composeTestRule.onNodeWithTag(C.Tag.careCircleMember(sophie.uid)).performClick()
    composeTestRule.runOnIdle {
      assertEquals(
          string(R.string.care_circle_member_not_implemented, "Sophie"),
          ShadowToast.getTextOfLatestToast(),
      )
    }
  }

  @Test
  fun screen_addingAMember_explainsItIsNotImplemented() {
    setScreen(listOf(sophie))
    composeTestRule.onNodeWithTag(C.Tag.care_circle_add_member_fab).performClick()
    composeTestRule.runOnIdle {
      assertEquals(
          string(R.string.care_circle_add_member_not_implemented),
          ShadowToast.getTextOfLatestToast(),
      )
    }
  }

  @Test
  fun screen_emptyCircle_addButtonExplainsItIsNotImplemented() {
    setScreen(emptyList())
    composeTestRule.onNodeWithTag(C.Tag.care_circle_empty_add_button).performClick()
    composeTestRule.runOnIdle {
      assertEquals(
          string(R.string.care_circle_add_member_not_implemented),
          ShadowToast.getTextOfLatestToast(),
      )
    }
  }
}
