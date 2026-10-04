package com.github.se.oncompanion.ui.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

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
        .onNodeWithText(string(R.string.onboarding_coming_soon), useUnmergedTree = true)
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
  fun content_caregiverCard_isDisabled_neverSelected_andClickDoesNothing() {
    setContent(null)
    val caregiver = composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver)
    caregiver.assertIsNotEnabled()
    caregiver.assertIsNotSelected()

    caregiver.performScrollTo().performClick()
    composeTestRule.runOnIdle { assertTrue(selectedRoles.isEmpty()) }
    caregiver.assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).assertIsNotEnabled()
  }

  @Test
  fun content_caregiverIsNotSelected_evenWhenCaregiverIsTheSelectedRole() {
    setContent(Role.CAREGIVER)
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsNotSelected()
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
      RoleScreen(viewModel = viewModel, onContinue = { continueClicks++ })
    }
    return viewModel
  }

  @Test
  fun screen_showsPatientSelectedByDefault() {
    val viewModel = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_screen).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotSelected()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).assertIsNotEnabled()
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
  fun screen_clickingCaregiver_doesNotChangeTheRole() {
    val viewModel = setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_caregiver).performScrollTo().performClick()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_role_patient).assertIsSelected()
    composeTestRule.runOnIdle { assertEquals(Role.PATIENT, viewModel.uiState.value.role) }
  }

  @Test
  fun screen_clickingContinue_callsOnContinue() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_continue_button).performScrollTo().performClick()
    composeTestRule.runOnIdle { assertEquals(1, continueClicks) }
  }
}
