package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.resources.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInContentTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Everything the content reported, in order: "signIn" or "errorShown". */
  private val events = mutableListOf<String>()

  private var uiState by mutableStateOf(SignInUiState())

  /** Shows SignInContent like the real screen does: onErrorShown clears the error. */
  private fun setContent(initial: SignInUiState) {
    uiState = initial
    composeTestRule.setContent {
      SignInContent(
          uiState = uiState,
          onSignInClick = { events += "signIn" },
          onErrorShown = {
            events += "errorShown"
            uiState = uiState.copy(error = null)
          },
      )
    }
  }

  private fun string(id: Int) = context.getString(id)

  private fun waitForText(text: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForNoText(text: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }
  }

  @Test
  fun idle_showsWelcomeSubtitleAndEnabledButton() {
    setContent(SignInUiState())
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.sign_in_welcome)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.sign_in_subtitle)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsDisplayed().assertIsEnabled()
    composeTestRule
        .onNodeWithText(string(R.string.sign_in_with_google), useUnmergedTree = true)
        .assertIsDisplayed()
  }

  @Test
  fun idle_showsNoLoadingIndicatorAndNoSnackbar() {
    setContent(SignInUiState())
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertDoesNotExist()
    composeTestRule.runOnIdle { assertTrue(events.isEmpty()) }
  }

  @Test
  fun clickingTheButton_callsOnSignInClick() {
    setContent(SignInUiState())
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).performClick()
    composeTestRule.runOnIdle { assertEquals(listOf("signIn"), events) }
  }

  @Test
  fun loading_disablesTheButtonAndShowsTheIndicator() {
    setContent(SignInUiState(isLoading = true))
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsNotEnabled().performClick()
    composeTestRule.runOnIdle { assertTrue(events.isEmpty()) }
  }

  @Test
  fun loadingFinished_hidesTheIndicatorAndReenablesTheButton() {
    setContent(SignInUiState(isLoading = true))
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertIsDisplayed()

    uiState = SignInUiState(isLoading = false)

    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsEnabled()
  }

  @Test
  fun noConnectionError_showsItsMessageWithRetry() {
    assertSnackbarFor(SignInError.NO_CONNECTION, R.string.sign_in_error_no_connection)
  }

  @Test
  fun noGoogleAccountError_showsItsMessageWithRetry() {
    assertSnackbarFor(SignInError.NO_GOOGLE_ACCOUNT, R.string.sign_in_error_no_google_account)
  }

  @Test
  fun failedError_showsItsMessageWithRetry() {
    assertSnackbarFor(SignInError.FAILED, R.string.sign_in_error_failed)
  }

  private fun assertSnackbarFor(error: SignInError, messageRes: Int) {
    setContent(SignInUiState(error = error))
    waitForText(string(messageRes))
    composeTestRule.onNodeWithText(string(messageRes)).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertIsDisplayed()
    // Only the message for this error is shown
    val otherMessages =
        listOf(
                R.string.sign_in_error_no_connection,
                R.string.sign_in_error_no_google_account,
                R.string.sign_in_error_failed,
            )
            .filter { it != messageRes }
    otherMessages.forEach { composeTestRule.onNodeWithText(string(it)).assertDoesNotExist() }
  }

  @Test
  fun errorAppearingLater_showsTheSnackbar() {
    setContent(SignInUiState(isLoading = true))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertDoesNotExist()

    uiState = SignInUiState(error = SignInError.NO_CONNECTION)

    waitForText(string(R.string.sign_in_error_no_connection))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertIsDisplayed()
  }

  @Test
  fun clickingRetry_dismissesSnackbar_thenReportsErrorShownAndSignsInAgain() {
    setContent(SignInUiState(error = SignInError.FAILED))
    waitForText(string(R.string.sign_in_retry))

    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).performClick()

    waitForNoText(string(R.string.sign_in_error_failed))
    composeTestRule.runOnIdle { assertEquals(listOf("errorShown", "signIn"), events) }
  }

  @Test
  fun dismissingSnackbar_reportsErrorShownWithoutSigningIn() {
    setContent(SignInUiState(error = SignInError.NO_GOOGLE_ACCOUNT))
    waitForText(string(R.string.sign_in_error_no_google_account))

    composeTestRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        .performSemanticsAction(SemanticsActions.Dismiss)

    waitForNoText(string(R.string.sign_in_error_no_google_account))
    composeTestRule.runOnIdle { assertEquals(listOf("errorShown"), events) }
  }

  @Test
  fun sameErrorAgainAfterRetry_showsTheSnackbarAgain() {
    setContent(SignInUiState(error = SignInError.NO_CONNECTION))
    waitForText(string(R.string.sign_in_retry))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).performClick()
    waitForNoText(string(R.string.sign_in_error_no_connection))

    // The retry fails the same way
    uiState = SignInUiState(error = SignInError.NO_CONNECTION)

    waitForText(string(R.string.sign_in_error_no_connection))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertIsDisplayed()
  }
}
