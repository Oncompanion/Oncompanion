// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.carecircle

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.carecircle.CareCircleInvite
import com.github.se.oncompanion.model.carecircle.CareCircleMemberDetails
import com.github.se.oncompanion.model.carecircle.FakeCareCircleInviteRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsAboveKeyboard
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.showKeyboard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class JoinCareCircleScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

  private val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
  private val hasNoError = SemanticsMatcher.keyNotDefined(SemanticsProperties.Error)

  private fun node(tag: String) = composeTestRule.onNodeWithTag(tag)

  private fun assertText(text: String) {
    composeTestRule.onNodeWithText(text, useUnmergedTree = true).assertExists()
  }

  // --- JoinCareCircleContent ---

  private var uiState by mutableStateOf(JoinCareCircleUiState())
  private val events = mutableListOf<String>()

  /** Shows the content like the real screen does: typing updates the state. */
  private fun setContent(initial: JoinCareCircleUiState) {
    uiState = initial
    composeTestRule.setContent {
      JoinCareCircleContent(
          uiState = uiState,
          onCodeChange = { uiState = uiState.copy(code = it, error = null) },
          onJoin = { events += "join" },
          onDone = { events += "done" },
          onOpenProfile = { events += "profile" },
      )
    }
  }

  @Test
  fun content_empty_showsTheForm_withJoinDisabled() {
    setContent(JoinCareCircleUiState())
    node(C.Tag.join_circle_screen).assertIsDisplayed()
    assertText(string(R.string.join_circle_title))
    assertText(string(R.string.join_circle_subtitle))
    assertText(string(R.string.join_circle_code_hint))
    node(C.Tag.join_circle_code_field).assert(hasNoError)
    node(C.Tag.join_circle_join_button).assertIsNotEnabled()
    // The waiting state: what to do without a code, and the profile
    assertText(string(R.string.join_circle_no_code_hint))
    node(C.Tag.join_circle_profile).assertIsEnabled()
    node(C.Tag.join_circle_joined).assertDoesNotExist()
  }

  @Test
  fun content_typingAFullCode_enablesJoin_andJoinCallsOnJoin() {
    setContent(JoinCareCircleUiState())
    node(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    node(C.Tag.join_circle_code_field).assertTextContains("abcde-fghjk")
    node(C.Tag.join_circle_join_button).assertIsEnabled().performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("join"), events) }
  }

  @Test
  fun content_keyboardDone_joins() {
    setContent(JoinCareCircleUiState(code = "ABCDEFGHJK"))
    node(C.Tag.join_circle_code_field).performImeAction()
    composeTestRule.runOnIdle { assertEquals(listOf("join"), events) }
  }

  @Test
  fun content_profile_callsOnOpenProfile() {
    setContent(JoinCareCircleUiState())
    node(C.Tag.join_circle_profile).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("profile"), events) }
  }

  @Test
  fun content_joined_stillOffersTheProfile_butNotTheNoCodeHint() {
    setContent(JoinCareCircleUiState(joinedPatientFirstName = "Marie"))
    node(C.Tag.join_circle_profile).assertIsEnabled()
    node(C.Tag.join_circle_no_code_hint).assertDoesNotExist()
  }

  @Test
  fun content_eachError_showsItsMessageUnderTheField() {
    val messages =
        mapOf(
            JoinCareCircleError.INVALID_CODE to R.string.join_circle_error_invalid,
            JoinCareCircleError.OFFLINE to R.string.join_circle_error_offline,
            JoinCareCircleError.NOT_SIGNED_IN to R.string.join_circle_error_not_signed_in,
            JoinCareCircleError.FAILED to R.string.join_circle_error_failed,
        )
    setContent(JoinCareCircleUiState(code = "ABCDEFGHJK"))
    messages.forEach { (error, message) ->
      uiState = JoinCareCircleUiState(code = "ABCDEFGHJK", error = error)
      composeTestRule.waitForIdle()
      node(C.Tag.join_circle_code_field).assert(hasError)
      assertText(string(message))
    }
  }

  @Test
  fun content_joining_showsASpinner_andDisablesEverything() {
    setContent(JoinCareCircleUiState(code = "ABCDEFGHJK", isJoining = true))
    node(C.Tag.join_circle_joining).assertExists()
    node(C.Tag.join_circle_join_button).assertIsNotEnabled()
    node(C.Tag.join_circle_code_field).assertIsNotEnabled()
  }

  @Test
  fun content_joined_showsWhoseCircle_andContinueCallsOnDone() {
    setContent(JoinCareCircleUiState(code = "ABCDEFGHJK", joinedPatientFirstName = "Marie"))
    node(C.Tag.join_circle_joined).assertIsDisplayed()
    assertText(string(R.string.join_circle_joined, "Marie"))
    assertText(string(R.string.join_circle_joined_hint))
    node(C.Tag.join_circle_code_field).assertDoesNotExist()

    node(C.Tag.join_circle_continue).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("done"), events) }
  }

  @Test
  fun content_alreadyMember_saysSo() {
    setContent(JoinCareCircleUiState(joinedPatientFirstName = "Marie", wasAlreadyMember = true))
    assertText(string(R.string.join_circle_already_member, "Marie"))
  }

  @Test
  fun content_joined_withoutTheirName_namesNobody() {
    setContent(JoinCareCircleUiState(joinedPatientFirstName = ""))
    assertText(string(R.string.join_circle_joined_unnamed))
    composeTestRule.onNodeWithText("'s care circle", substring = true).assertDoesNotExist()
  }

  @Test
  fun content_alreadyMember_withoutTheirName_namesNobody() {
    setContent(JoinCareCircleUiState(joinedPatientFirstName = " ", wasAlreadyMember = true))
    assertText(string(R.string.join_circle_already_member_unnamed))
  }

  @Test
  fun content_keyboardOpen_joinCanBeScrolledAboveIt() {
    setContent(JoinCareCircleUiState(code = "ABCDEFGHJK"))
    val keyboardHeight = 600

    node(C.Tag.join_circle_code_field).requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    node(C.Tag.join_circle_join_button).performScrollTo().assertIsAboveKeyboard(keyboardHeight)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun content_landscapeKeyboardOpen_keepsTheCodeFieldVisible() {
    setContent(JoinCareCircleUiState())
    val keyboardHeight = 500

    // Focus without a tap: a tap can open the text handle, a window of its own
    node(C.Tag.join_circle_code_field).performScrollTo().requestFocus()
    composeTestRule.showKeyboard(keyboardHeight)

    node(C.Tag.join_circle_code_field).performScrollTo().assertIsFullyVisible()
    node(C.Tag.join_circle_code_field).assertIsAboveKeyboard(keyboardHeight)
  }

  // --- JoinCareCircleScreen with a view model ---

  @Test
  fun screen_typingAValidCodeAndJoining_showsTheConfirmation() {
    val auth = FakeAuthRepository(onSignIn = { AuthUser(uid = "caregiver") })
    runBlocking { auth.signInWithGoogle("token") }
    val invites = FakeCareCircleInviteRepository()
    invites.setInvites(
        listOf(
            CareCircleInvite(
                inviteCode = "ABCDEFGHJK",
                ownerUid = "patient",
                ownerFirstName = "Marie",
                details = CareCircleMemberDetails(firstName = "Sam"),
            )
        )
    )
    var doneCalls = 0
    val viewModel = JoinCareCircleViewModel(auth, invites)
    composeTestRule.setContent {
      JoinCareCircleScreen(
          viewModel = viewModel,
          onDone = { doneCalls++ },
          onOpenProfile = {},
          onSignedOut = {},
      )
    }

    node(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    node(C.Tag.join_circle_join_button).performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodesWithTag(C.Tag.join_circle_joined).fetchSemanticsNodes().isNotEmpty()
    }
    assertText(string(R.string.join_circle_joined, "Marie"))
    node(C.Tag.join_circle_continue).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(1, doneCalls) }
  }

  @Test
  fun screen_signedOut_joiningSendsToSignIn() {
    var signedOutCalls = 0
    val viewModel = JoinCareCircleViewModel(FakeAuthRepository(), FakeCareCircleInviteRepository())
    composeTestRule.setContent {
      JoinCareCircleScreen(
          viewModel = viewModel,
          onDone = {},
          onOpenProfile = {},
          onSignedOut = { signedOutCalls++ },
      )
    }

    node(C.Tag.join_circle_code_field).performTextInput("abcde-fghjk")
    node(C.Tag.join_circle_join_button).performScrollTo().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { signedOutCalls > 0 }
    composeTestRule.runOnIdle { assertEquals(1, signedOutCalls) }
  }
}
