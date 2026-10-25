// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleMember
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.model.carecircle.FakeCareCircleRepository
import com.github.se.oncompanion.model.carecircle.JoinedCareCircle
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhoseCareScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private val anne = FollowedPatient("p-anne", "Anne", setOf(CarePermission.PLANNING))
  private val paul =
      FollowedPatient("p-paul", "Paul", setOf(CarePermission.PLANNING, CarePermission.SYMPTOMS))

  private val events = mutableListOf<String>()

  // --- WhoseCareContent ---

  private fun setContent(uiState: WhoseCareUiState, withBack: Boolean = true) {
    composeTestRule.setContent {
      WhoseCareContent(
          uiState = uiState,
          onBack = if (withBack) ({ events += "back" }) else null,
          onPatientClick = { events += "chose $it" },
          onJoinAnother = { events += "join" },
          onRetry = { events += "retry" },
      )
    }
  }

  @Test
  fun content_loading_showsOnlyTheSpinner() {
    setContent(WhoseCareUiState())
    node(C.Tag.whose_care_loading).assertIsDisplayed()
    node(C.Tag.whose_care_join_another).assertDoesNotExist()
  }

  @Test
  fun content_patients_showTheirNameAndWhatTheyShare() {
    setContent(WhoseCareUiState(patients = listOf(anne, paul), isLoading = false))
    composeTestRule.onNodeWithText(string(R.string.whose_care_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.whose_care_count, 2)).assertIsDisplayed()
    node(C.Tag.whoseCarePatient("p-anne")).assertIsDisplayed()
    composeTestRule.onNodeWithText("Planning", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("Planning & symptoms", useUnmergedTree = true).assertExists()
  }

  @Test
  fun content_tappingAPatient_choosesThem() {
    setContent(WhoseCareUiState(patients = listOf(anne, paul), isLoading = false))
    node(C.Tag.whoseCarePatient("p-paul")).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("chose p-paul"), events) }
  }

  @Test
  fun content_joinAnother_opensTheJoinScreen() {
    setContent(WhoseCareUiState(patients = listOf(anne), isLoading = false))
    node(C.Tag.whose_care_join_another).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("join"), events) }
  }

  @Test
  fun content_backArrow_onlyWhenThereIsSomewhereToGoBack() {
    setContent(WhoseCareUiState(patients = listOf(anne), isLoading = false), withBack = true)
    node(C.Tag.whose_care_back).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("back"), events) }
  }

  @Test
  fun content_firstScreen_hasNoBackArrow() {
    setContent(WhoseCareUiState(patients = listOf(anne), isLoading = false), withBack = false)
    node(C.Tag.whose_care_back).assertDoesNotExist()
  }

  @Test
  fun content_error_retries() {
    setContent(WhoseCareUiState(isLoading = false, hasError = true))
    node(C.Tag.whose_care_error).assertIsDisplayed()
    node(C.Tag.whose_care_action).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("retry"), events) }
  }

  @Test
  fun content_noPatient_offersToJoin() {
    setContent(WhoseCareUiState(isLoading = false))
    node(C.Tag.whose_care_empty).assertIsDisplayed()
    node(C.Tag.whose_care_join_another).assertDoesNotExist()
    node(C.Tag.whose_care_action).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("join"), events) }
  }

  // --- WhoseCareScreen with a view model ---

  private fun viewModelFollowing(vararg patients: Pair<String, String>): WhoseCareViewModel {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "caregiver") })
    runBlocking { auth.signInWithGoogle("token") }
    val invites = FakeCareCircleInviteRepository()
    val circles = FakeCareCircleRepository()
    for ((uid, _) in patients) {
      circles.setMembers(uid, listOf(CareCircleMember(uid = "caregiver", firstName = "Sam")))
    }
    invites.setJoinedCircles(
        "caregiver",
        patients.map { (uid, name) -> JoinedCareCircle(uid, name) },
    )
    return WhoseCareViewModel(auth, invites, circles)
  }

  private fun setScreen(viewModel: WhoseCareViewModel, autoSelectSingle: Boolean) {
    composeTestRule.setContent {
      WhoseCareScreen(
          viewModel = viewModel,
          onBack = null,
          onPatientChosen = { events += "chose $it" },
          onJoinAnother = {},
          autoSelectSingle = autoSelectSingle,
      )
    }
  }

  @Test
  fun screen_singlePatient_isChosenByItself_once() {
    setScreen(viewModelFollowing("p-anne" to "Anne"), autoSelectSingle = true)
    composeTestRule.waitUntil(timeoutMillis = 5_000) { events.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { assertEquals(listOf("chose p-anne"), events) }
  }

  @Test
  fun screen_switchingOnPurpose_showsTheListEvenWithOnePatient() {
    setScreen(viewModelFollowing("p-anne" to "Anne"), autoSelectSingle = false)
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodes(hasTestTag(C.Tag.whoseCarePatient("p-anne")))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.runOnIdle { assertEquals(emptyList<String>(), events) }
  }

  @Test
  fun screen_severalPatients_showsTheList() {
    setScreen(viewModelFollowing("p-anne" to "Anne", "p-paul" to "Paul"), autoSelectSingle = true)
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule
          .onAllNodes(hasTestTag(C.Tag.whoseCarePatient("p-paul")))
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.runOnIdle { assertEquals(emptyList<String>(), events) }
  }
}
