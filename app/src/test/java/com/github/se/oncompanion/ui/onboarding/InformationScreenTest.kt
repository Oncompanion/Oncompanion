package com.github.se.oncompanion.ui.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.cancer.CancerTypes
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InformationScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun string(id: Int) = context.getString(id)

  private val hasError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
  private val hasNoError = SemanticsMatcher.keyNotDefined(SemanticsProperties.Error)

  // --- InformationContent ---

  private var uiState by mutableStateOf(OnboardingUiState())
  private val events = mutableListOf<String>()
  private val firstNames = mutableListOf<String>()
  private val familyNames = mutableListOf<String>()
  private val cancerTypes = mutableListOf<String>()

  /** Shows InformationContent like the real screen does: callbacks update the state. */
  private fun setContent(initial: OnboardingUiState) {
    uiState = initial
    composeTestRule.setContent {
      InformationContent(
          uiState = uiState,
          onFirstNameChange = {
            firstNames += it
            uiState = uiState.copy(firstName = it)
          },
          onFamilyNameChange = {
            familyNames += it
            uiState = uiState.copy(familyName = it)
          },
          onCancerTypeChange = {
            cancerTypes += it
            uiState = uiState.copy(cancerType = it)
          },
          onContinue = { events += "continue" },
          onErrorShown = {
            events += "errorShown"
            uiState = uiState.copy(error = null)
          },
      )
    }
  }

  private fun waitForText(text: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForNoText(text: String) {
    composeTestRule.waitUntil(timeoutMillis = 10_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }
  }

  private fun firstNameField() = composeTestRule.onNodeWithTag(C.Tag.onboarding_first_name_field)

  private fun familyNameField() = composeTestRule.onNodeWithTag(C.Tag.onboarding_family_name_field)

  private fun cancerTypeField() = composeTestRule.onNodeWithTag(C.Tag.cancer_type_field)

  private fun continueButton() = composeTestRule.onNodeWithTag(C.Tag.onboarding_save_button)

  private fun assertTextExists(id: Int) {
    composeTestRule.onNodeWithText(string(id), useUnmergedTree = true).assertExists()
  }

  private fun assertTextDoesNotExist(id: Int) {
    composeTestRule.onNodeWithText(string(id), useUnmergedTree = true).assertDoesNotExist()
  }

  private fun assertFirstNameRequiredWithoutError() {
    firstNameField().assert(hasNoError)
    assertTextExists(R.string.onboarding_required)
    assertTextDoesNotExist(R.string.onboarding_first_name_missing)
  }

  private fun assertFirstNameMissingError() {
    firstNameField().assert(hasError)
    assertTextExists(R.string.onboarding_first_name_missing)
  }

  @Test
  fun content_showsTitleSubtitleFieldsAndContinue() {
    setContent(OnboardingUiState(firstName = "Alex"))
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(string(R.string.onboarding_information_title))
        .assertIsDisplayed()
    assertEquals("Tell us about yourself", string(R.string.onboarding_information_title))
    assertTextExists(R.string.onboarding_information_subtitle)

    firstNameField().assertExists()
    assertTextExists(R.string.onboarding_first_name)
    familyNameField().assertExists()
    assertTextExists(R.string.onboarding_family_name)
    cancerTypeField().assertExists()
    assertTextExists(R.string.cancer_type_label)

    continueButton().assertExists()
    assertTextExists(R.string.onboarding_continue)
    composeTestRule
        .onAllNodesWithTag(C.Tag.onboarding_saving, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .let { assertTrue(it.isEmpty()) }
  }

  @Test
  fun content_fieldsShowTheStateValues() {
    setContent(OnboardingUiState(firstName = "Alex", familyName = "Doe", cancerType = "Leukemia"))
    firstNameField().assertTextContains("Alex")
    familyNameField().assertTextContains("Doe")
    cancerTypeField().assertTextContains("Leukemia")
  }

  @Test
  fun content_emptyPrefill_onArrival_showsRequiredWithoutError_andContinueDisabled() {
    setContent(OnboardingUiState(firstName = ""))
    assertFirstNameRequiredWithoutError()
    continueButton().assertIsNotEnabled()

    continueButton().performClick()
    composeTestRule.runOnIdle { assertTrue(events.isEmpty()) }
  }

  @Test
  fun content_prefilledFirstName_showsRequiredWithoutError_andContinueEnabled() {
    setContent(OnboardingUiState(firstName = "Alex"))
    assertFirstNameRequiredWithoutError()
    continueButton().assertIsEnabled()
  }

  @Test
  fun content_typingFirstName_callsOnFirstNameChange_andEnablesContinue() {
    setContent(OnboardingUiState(firstName = ""))
    firstNameField().performTextInput("Sam")
    composeTestRule.runOnIdle { assertEquals("Sam", firstNames.last()) }
    firstNameField().assertTextContains("Sam")
    continueButton().assertIsEnabled()
    assertFirstNameRequiredWithoutError()
  }

  @Test
  fun content_typingThenClearingFirstName_showsMissingError_andDisablesContinue() {
    setContent(OnboardingUiState(firstName = ""))
    firstNameField().performTextInput("S")
    assertFirstNameRequiredWithoutError()

    firstNameField().performTextClearance()
    composeTestRule.runOnIdle { assertEquals("", firstNames.last()) }
    assertFirstNameMissingError()
    continueButton().assertIsNotEnabled()
  }

  @Test
  fun content_clearingPrefilledFirstName_showsMissingError() {
    setContent(OnboardingUiState(firstName = "Alex"))
    firstNameField().performTextClearance()
    assertFirstNameMissingError()
    continueButton().assertIsNotEnabled()
  }

  @Test
  fun content_blankFirstNameAfterEditing_showsMissingError() {
    setContent(OnboardingUiState(firstName = "Alex"))
    firstNameField().performTextReplacement("   ")
    assertFirstNameMissingError()
    continueButton().assertIsNotEnabled()
  }

  @Test
  fun content_retypingFirstNameAfterError_removesTheError() {
    setContent(OnboardingUiState(firstName = "Alex"))
    firstNameField().performTextClearance()
    assertFirstNameMissingError()

    firstNameField().performTextInput("Sam")
    assertFirstNameRequiredWithoutError()
    continueButton().assertIsEnabled()
  }

  @Test
  fun content_typingFamilyName_callsOnFamilyNameChange_andIsNeverAnError() {
    setContent(OnboardingUiState(firstName = "Alex"))
    familyNameField().assert(hasNoError)
    familyNameField().performTextInput("Doe")
    composeTestRule.runOnIdle { assertEquals("Doe", familyNames.last()) }
    familyNameField().assertTextContains("Doe")

    familyNameField().performTextClearance()
    composeTestRule.runOnIdle { assertEquals("", familyNames.last()) }
    familyNameField().assert(hasNoError)
    continueButton().assertIsEnabled()
    composeTestRule.runOnIdle { assertTrue(firstNames.isEmpty()) }
  }

  @Test
  fun content_typingCancerType_callsOnCancerTypeChange() {
    setContent(OnboardingUiState(firstName = "Alex"))
    cancerTypeField().performTextInput("qqqq")
    composeTestRule.runOnIdle { assertEquals("qqqq", cancerTypes.last()) }
    cancerTypeField().assertTextContains("qqqq")
  }

  @Test
  fun content_pickingCancerTypeSuggestion_callsOnCancerTypeChangeWithIt() {
    setContent(OnboardingUiState(firstName = "Alex"))
    cancerTypeField().performTextInput("leuk")
    composeTestRule.waitForIdle()
    val first = CancerTypes.suggest("leuk").first()
    composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion)[0].performClick()
    composeTestRule.runOnIdle { assertEquals(first, cancerTypes.last()) }
    cancerTypeField().assertTextContains(first)
  }

  @Test
  fun content_clickingContinue_callsOnContinue() {
    setContent(OnboardingUiState(firstName = "Alex"))
    continueButton().performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("continue"), events) }
  }

  @Test
  fun content_saving_showsProgressInsteadOfText_andDisablesContinue() {
    setContent(OnboardingUiState(firstName = "Alex", isSaving = true))
    composeTestRule.onNodeWithTag(C.Tag.onboarding_saving, useUnmergedTree = true).assertExists()
    assertTextDoesNotExist(R.string.onboarding_continue)
    continueButton().assertIsNotEnabled()

    continueButton().performClick()
    composeTestRule.runOnIdle { assertTrue(events.isEmpty()) }
  }

  @Test
  fun content_savingFinishedWithError_showsTextAgainWithoutProgress() {
    setContent(OnboardingUiState(firstName = "Alex", isSaving = true))
    composeTestRule.onNodeWithTag(C.Tag.onboarding_saving, useUnmergedTree = true).assertExists()

    uiState = uiState.copy(isSaving = false)
    composeTestRule
        .onNodeWithTag(C.Tag.onboarding_saving, useUnmergedTree = true)
        .assertDoesNotExist()
    assertTextExists(R.string.onboarding_continue)
    continueButton().assertIsEnabled()
  }

  @Test
  fun content_saved_disablesContinue() {
    setContent(OnboardingUiState(firstName = "Alex", isSaved = true))
    continueButton().assertIsNotEnabled()
    composeTestRule
        .onNodeWithTag(C.Tag.onboarding_saving, useUnmergedTree = true)
        .assertDoesNotExist()
  }

  @Test
  fun content_noError_showsNoSnackbar() {
    setContent(OnboardingUiState(firstName = "Alex"))
    composeTestRule.waitForIdle()
    assertTextDoesNotExist(R.string.onboarding_error_not_signed_in)
    assertTextDoesNotExist(R.string.onboarding_error_save_failed)
    composeTestRule.runOnIdle { assertTrue(events.isEmpty()) }
  }

  @Test
  fun content_notSignedInError_showsItsSnackbar() {
    setContent(OnboardingUiState(firstName = "Alex", error = OnboardingError.NOT_SIGNED_IN))
    waitForText(string(R.string.onboarding_error_not_signed_in))
    composeTestRule.onNodeWithText(string(R.string.onboarding_error_not_signed_in)).assertExists()
    assertTextDoesNotExist(R.string.onboarding_error_save_failed)
  }

  @Test
  fun content_saveFailedError_showsItsSnackbar() {
    setContent(OnboardingUiState(firstName = "Alex", error = OnboardingError.SAVE_FAILED))
    waitForText(string(R.string.onboarding_error_save_failed))
    composeTestRule.onNodeWithText(string(R.string.onboarding_error_save_failed)).assertExists()
    assertTextDoesNotExist(R.string.onboarding_error_not_signed_in)
  }

  @Test
  fun content_errorAppearingLater_showsTheSnackbar() {
    setContent(OnboardingUiState(firstName = "Alex", isSaving = true))
    assertTextDoesNotExist(R.string.onboarding_error_save_failed)

    uiState = OnboardingUiState(firstName = "Alex", error = OnboardingError.SAVE_FAILED)
    waitForText(string(R.string.onboarding_error_save_failed))
  }

  @Test
  fun content_dismissingSnackbar_callsOnErrorShown() {
    setContent(OnboardingUiState(firstName = "Alex", error = OnboardingError.SAVE_FAILED))
    waitForText(string(R.string.onboarding_error_save_failed))

    composeTestRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        .performSemanticsAction(SemanticsActions.Dismiss)

    waitForNoText(string(R.string.onboarding_error_save_failed))
    composeTestRule.runOnIdle {
      assertEquals(listOf("errorShown"), events)
      assertNull(uiState.error)
    }
  }

  @Test
  fun content_snackbarTimingOut_callsOnErrorShown() {
    setContent(OnboardingUiState(firstName = "Alex", error = OnboardingError.NOT_SIGNED_IN))
    waitForText(string(R.string.onboarding_error_not_signed_in))

    waitForNoText(string(R.string.onboarding_error_not_signed_in))
    composeTestRule.runOnIdle { assertEquals(listOf("errorShown"), events) }
  }

  // --- InformationScreen with a view model ---

  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private var savedCalls = 0

  private fun signIn(user: AuthUser = AuthUser(uid = "uid-1")) {
    auth.onSignIn = { user }
    runBlocking { auth.signInWithGoogle("token") }
  }

  private fun setScreen(): OnboardingViewModel {
    val viewModel = OnboardingViewModel(auth, profiles)
    composeTestRule.setContent {
      InformationScreen(viewModel = viewModel, onSaved = { savedCalls++ })
    }
    return viewModel
  }

  @Test
  fun screen_showsThePrefilledFirstName_withoutError() {
    signIn(AuthUser(uid = "uid-1", givenName = "Alexandra"))
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.onboarding_information_screen).assertIsDisplayed()
    firstNameField().assertTextContains("Alexandra")
    assertFirstNameRequiredWithoutError()
    continueButton().assertIsEnabled()
  }

  @Test
  fun screen_emptyPrefill_continueDisabled_withoutError() {
    signIn()
    setScreen()
    assertFirstNameRequiredWithoutError()
    continueButton().assertIsNotEnabled()
  }

  @Test
  fun screen_typingUpdatesTheViewModel() {
    signIn()
    val viewModel = setScreen()
    firstNameField().performTextInput("Sam")
    familyNameField().performTextInput("Doe")
    cancerTypeField().performTextInput("qqqq")
    composeTestRule.runOnIdle {
      val state = viewModel.uiState.value
      assertEquals("Sam", state.firstName)
      assertEquals("Doe", state.familyName)
      assertEquals("qqqq", state.cancerType)
    }
  }

  @Test
  fun screen_fillAndContinue_savesTheProfile_andCallsOnSavedOnce() {
    signIn()
    val viewModel = setScreen()
    firstNameField().performTextInput("  Sam  ")
    familyNameField().performTextInput("Doe")
    cancerTypeField().performTextInput("leuk")
    composeTestRule.waitForIdle()
    val cancerType = CancerTypes.suggest("leuk").first()
    composeTestRule.onAllNodesWithTag(C.Tag.cancer_type_suggestion)[0].performClick()
    composeTestRule.waitForIdle()

    continueButton().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { savedCalls > 0 }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle {
      assertEquals(1, savedCalls)
      assertTrue(viewModel.uiState.value.isSaved)
      val saved = profiles.profiles["uid-1"]!!
      assertEquals(Role.PATIENT, saved.role)
      assertEquals("Sam", saved.firstName)
      assertEquals("Doe", saved.familyName)
      assertEquals(cancerType, saved.cancerType)
    }
    continueButton().assertIsNotEnabled()
  }

  @Test
  fun screen_blankOptionalFields_areStoredAsNull() {
    signIn(AuthUser(uid = "uid-1", givenName = "Alexandra"))
    setScreen()
    familyNameField().performTextInput("   ")
    continueButton().performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { savedCalls > 0 }
    composeTestRule.runOnIdle {
      assertEquals(1, savedCalls)
      val saved = profiles.profiles["uid-1"]!!
      assertEquals("Alexandra", saved.firstName)
      assertEquals(Role.PATIENT, saved.role)
      assertNull(saved.familyName)
      assertNull(saved.cancerType)
    }
  }

  @Test
  fun screen_writeError_showsSaveFailedSnackbar_andDoesNotCallOnSaved() {
    signIn(AuthUser(uid = "uid-1", givenName = "Alexandra"))
    profiles.writeError = RuntimeException("boom")
    val viewModel = setScreen()
    continueButton().performClick()

    waitForText(string(R.string.onboarding_error_save_failed))
    composeTestRule.runOnIdle {
      assertEquals(0, savedCalls)
      assertTrue(profiles.profiles.isEmpty())
      assertTrue(!viewModel.uiState.value.isSaved)
    }
    // The user can try again
    continueButton().assertIsEnabled()
  }

  @Test
  fun screen_signedOut_showsNotSignedInSnackbar_andDoesNotCallOnSaved() {
    val viewModel = setScreen()
    firstNameField().performTextInput("Sam")
    continueButton().performClick()

    waitForText(string(R.string.onboarding_error_not_signed_in))
    composeTestRule.runOnIdle {
      assertEquals(0, savedCalls)
      assertTrue(profiles.profiles.isEmpty())
      assertTrue(!viewModel.uiState.value.isSaved)
    }
  }

  @Test
  fun screen_dismissingSnackbar_clearsTheViewModelError() {
    val viewModel = setScreen()
    firstNameField().performTextInput("Sam")
    continueButton().performClick()
    waitForText(string(R.string.onboarding_error_not_signed_in))

    composeTestRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        .performSemanticsAction(SemanticsActions.Dismiss)

    waitForNoText(string(R.string.onboarding_error_not_signed_in))
    composeTestRule.runOnIdle { assertNull(viewModel.uiState.value.error) }
  }
}
