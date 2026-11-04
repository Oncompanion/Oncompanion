// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.onboarding

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.session.SessionAccess
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleScreenTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int) = context.getString(id)

  private val selectedRoles = mutableListOf<Role>()
  private var continueClicks = 0

  private fun setContent(initial: Role?) {
    composeTestRule.setContent {
      var selected by remember { mutableStateOf(initial) }
      RoleContent(
          selectedRole = selected,
          onRoleSelected = {
            selectedRoles += it
            selected = it
          },
          onContinue = { continueClicks++ },
      )
    }
  }

  // --- RoleContent ---

  @Test
  fun content_showsTitleSubtitleAndBothCards() {
    setContent(Role.PATIENT)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.onboarding_role_title)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.onboarding_role_subtitle)).assertIsDisplayed()

    composeTestRule
        .onNodeWithTag(C.Tag.onboarding_role_patient)
        .performScrollTo()
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.onboarding_role_patient), useUnmergedTree = true)
        .assertExists()
    composeTestRule
        .onNodeWithText(
            string(R.string.onboarding_role_patient_description),
            useUnmergedTree = true,
        )
        .assertExists()

    composeTestRule
        .onNodeWithTag(C.Tag.onboarding_role_caregiver)
        .performScrollTo()
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.onboarding_role_caregiver), useUnmergedTree = true)
        .assertExists()
    composeTestRule
        .onNodeWithText(
            string(R.string.onboarding_role_caregiver_description),
            useUnmergedTree = true,
        )
        .assertExists()

    composeTestRule
        .onNodeWithTag(C.Tag.onboarding_continue_button)
        .performScrollTo()
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.onboarding_continue), useUnmergedTree = true)
        .assertExists()
  }

  @Test
  fun content_patientSelected_patientCardIsSelected_andContinueEnabled() {
    setContent(Role.PATIENT)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
  }

  @Test
  fun content_noSelection_nothingSelected_andContinueDisabled() {
    setContent(null)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsNotEnabled()

    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(0, continueClicks) }
  }

  @Test
  fun content_clickingPatient_selectsPatient_andEnablesContinue() {
    setContent(null)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(listOf(Role.PATIENT), selectedRoles) }
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
  }

  @Test
  fun content_clickingCaregiver_selectsCaregiver_andEnablesContinue() {
    setContent(null)
    val caregiver = composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver)
    caregiver.assertIsEnabled()
    caregiver.assertIsNotSelected()

    caregiver.performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(listOf(Role.CAREGIVER), selectedRoles) }
    caregiver.assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
  }

  @Test
  fun content_caregiverSelected_onlyTheCaregiverCardIsSelected() {
    setContent(Role.CAREGIVER)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsNotSelected()
  }

  @Test
  fun content_switchingFromCaregiverToPatient_selectsPatient() {
    setContent(Role.CAREGIVER)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.runOnIdle { assertEquals(listOf(Role.PATIENT), selectedRoles) }
  }

  @Test
  fun content_clickingContinue_callsOnContinue() {
    setContent(Role.PATIENT)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.runOnIdle {
      assertEquals(1, continueClicks)
      assertTrue(selectedRoles.isEmpty())
    }
  }

  // --- RoleScreen with a view model ---

  private fun setScreen(): OnboardingViewModel {
    val viewModel = OnboardingViewModel(FakeAuthRepository(), FakeUserProfileRepository())
    composeTestRule.setContent {
      RoleScreen(viewModel = viewModel, onContinue = { continueClicks++ }, onSignedOut = {})
    }
    return viewModel
  }

  @Test
  fun screen_showsPatientSelectedByDefault() {
    val viewModel = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsEnabled()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsEnabled()
    composeTestRule.runOnIdle { assertEquals(Role.PATIENT, viewModel.uiState.value.role) }
  }

  @Test
  fun screen_clickingPatient_keepsPatientInTheViewModel() {
    val viewModel = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.runOnIdle { assertEquals(Role.PATIENT, viewModel.uiState.value.role) }
  }

  @Test
  fun screen_clickingCaregiver_storesCaregiverInTheViewModel() {
    val viewModel = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsNotSelected()
    composeTestRule.runOnIdle { assertEquals(Role.CAREGIVER, viewModel.uiState.value.role) }
  }

  @Test
  fun screen_clickingContinue_callsOnContinue() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(1, continueClicks) }
  }

  // --- Use another account (#281) ---

  @Test
  fun content_showsTheAccount_andUseAnotherAccountWhenItCanBeUsed() {
    var email by mutableStateOf<String?>("alex.martin@example.com")
    var enabled by mutableStateOf(true)
    var clicks = 0
    composeTestRule.setContent {
      RoleContent(
          selectedRole = Role.PATIENT,
          onRoleSelected = {},
          onContinue = {},
          email = email,
          canSwitchAccount = enabled,
          onUseAnotherAccount = { clicks++ },
      )
    }
    composeTestRule
        .onNodeWithText(
            context.getString(R.string.onboarding_signed_in_as, "alex.martin@example.com")
        )
        .performScrollTo()
        .assertIsDisplayed()
    button().performScrollTo().assertTextEquals(string(R.string.onboarding_use_another_account))
    button().performClick()
    composeTestRule.runOnIdle { assertEquals(1, clicks) }

    // Unknown account: only the button; while signing out or saving: disabled
    email = null
    enabled = false
    composeTestRule.onNodeWithTag(C.Tag.onboarding_signed_in_as).assertDoesNotExist()
    button().assertIsNotEnabled()
  }

  private val local = FakeLocalSessionRepository()
  private val credentials = FakeGoogleCredentialProvider()
  private var signedOut = 0

  @After
  fun reopenSession() {
    SessionAccess.reopen()
  }

  private fun button() = composeTestRule.onNodeWithTag(C.Tag.onboarding_use_another_account)

  /** The role step with a real sign-out on fakes, as when the app runs. */
  private fun switchingScreen(): OnboardingViewModel {
    val auth = FakeAuthRepository()
    runBlocking { auth.signInWithGoogle("1") }
    val signOut = SignOut(auth, local).apply { initialize(FakeCleanupJournal()) }
    val viewModel = OnboardingViewModel(auth, FakeUserProfileRepository(), signOut)
    showRole(viewModel)
    return viewModel
  }

  private fun showRole(viewModel: OnboardingViewModel) {
    composeTestRule.setContent {
      RoleScreen(
          viewModel = viewModel,
          onContinue = {},
          onSignedOut = { signedOut++ },
          credentialProvider = credentials,
      )
    }
  }

  @Test
  fun screen_useAnotherAccount_signsOut_andForgetsTheChosenAccount() {
    switchingScreen()
    button().performScrollTo().performClick()
    composeTestRule.waitUntil { signedOut == 1 }
    assertEquals(1, credentials.clearRequests.size)
    composeTestRule.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertDoesNotExist()
  }

  @Test
  fun screen_changesThatMayNotBeSaved_askFirst_staying_keepsOnboarding() {
    local.synchronizeAction = { error("offline") }
    val viewModel = switchingScreen()
    button().performScrollTo().performClick()

    composeTestRule.waitUntil {
      viewModel.uiState.value.accountSwitch == AccountSwitch.CONFIRMING_DISCARD
    }
    composeTestRule.onNodeWithText(string(R.string.profile_sign_out_unsynced)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_sign_out_cancel).performClick()

    composeTestRule.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertDoesNotExist()
    button().performScrollTo().assertIsEnabled()
    assertEquals(0, signedOut)
    assertEquals(0, credentials.clearRequests.size)
  }

  @Test
  fun screen_discardingTheChanges_signsOut() {
    local.synchronizeAction = { error("offline") }
    val viewModel = switchingScreen()
    button().performScrollTo().performClick()
    composeTestRule.waitUntil {
      viewModel.uiState.value.accountSwitch == AccountSwitch.CONFIRMING_DISCARD
    }

    composeTestRule.onNodeWithTag(C.Tag.profile_sign_out_confirm).performClick()

    composeTestRule.waitUntil { signedOut == 1 }
    assertEquals(1, credentials.clearRequests.size)
  }

  @Test
  fun phoneRotated_whileAskingToDiscard_stillAsks() {
    local.synchronizeAction = { error("offline") }
    val viewModel = switchingScreen()
    button().performScrollTo().performClick()
    composeTestRule.waitUntil {
      viewModel.uiState.value.accountSwitch == AccountSwitch.CONFIRMING_DISCARD
    }

    // Rotating recreates the activity; the onboarding ViewModel survives it
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.runOnUiThread {
      composeTestRule.activity.setContent {
        RoleScreen(
            viewModel = viewModel,
            onContinue = {},
            onSignedOut = { signedOut++ },
            credentialProvider = credentials,
        )
      }
    }

    composeTestRule.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertIsDisplayed()
    assertEquals(0, signedOut)
  }
}
