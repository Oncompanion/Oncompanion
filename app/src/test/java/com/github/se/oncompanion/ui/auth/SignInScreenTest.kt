package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.resources.C
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val user = AuthUser(uid = "uid-42", email = "alex@example.com", givenName = "Alex")

  private val repository = FakeAuthRepository(onSignIn = { user })
  private val provider = FakeGoogleCredentialProvider(onGetToken = { "google-id-token" })
  private val signedInUsers = mutableListOf<AuthUser>()
  private lateinit var screenContext: Context

  private fun setScreen() {
    val viewModel = SignInViewModel(repository)
    composeTestRule.setContent {
      screenContext = LocalContext.current
      SignInScreen(
          onSignedIn = { signedInUsers += it },
          viewModel = viewModel,
          credentialProvider = provider,
      )
    }
  }

  private fun string(id: Int) = context.getString(id)

  private fun clickSignIn() {
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).performClick()
  }

  private fun waitForText(text: String) {
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun showsTheSignInContent() {
    setScreen()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText(string(R.string.sign_in_welcome)).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsEnabled()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertTrue(provider.tokenRequests.isEmpty())
      assertTrue(signedInUsers.isEmpty())
    }
  }

  @Test
  fun successfulSignIn_usesTheProviderTokenAndCallsOnSignedInOnce() {
    setScreen()
    clickSignIn()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { signedInUsers.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle {
      assertEquals(1, provider.tokenRequests.size)
      assertSame(screenContext, provider.tokenRequests.single())
      assertEquals(listOf("google-id-token"), repository.signInTokens)
      assertEquals(listOf(user), signedInUsers)
    }
  }

  @Test
  fun whileSigningIn_showsLoadingAndDisablesTheButton() {
    val pendingToken = CompletableDeferred<String>()
    provider.onGetToken = { pendingToken.await() }
    setScreen()

    clickSignIn()

    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsNotEnabled()

    composeTestRule.runOnIdle { pendingToken.complete("late-token") }
    composeTestRule.waitUntil(timeoutMillis = 5_000) { signedInUsers.isNotEmpty() }
    composeTestRule.runOnIdle {
      assertEquals(listOf("late-token"), repository.signInTokens)
      assertEquals(listOf(user), signedInUsers)
    }
  }

  @Test
  fun cancelledSignIn_showsNoErrorAndReenablesTheButton() {
    provider.onGetToken = { throw GoogleSignInException(GoogleSignInException.Reason.CANCELLED) }
    setScreen()

    clickSignIn()

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsEnabled()
    composeTestRule.onNodeWithTag(C.Tag.sign_in_loading).assertDoesNotExist()
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertTrue(repository.signInTokens.isEmpty())
      assertTrue(signedInUsers.isEmpty())
    }
  }

  @Test
  fun noGoogleAccount_showsItsSnackbar() {
    provider.onGetToken = { throw GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT) }
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_no_google_account))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(signedInUsers.isEmpty()) }
  }

  @Test
  fun googleFailure_showsTheFailedSnackbar() {
    provider.onGetToken = { throw GoogleSignInException(GoogleSignInException.Reason.FAILED) }
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_failed))
  }

  @Test
  fun networkErrorFromFirebase_showsTheNoConnectionSnackbar() {
    repository.onSignIn = { throw FirebaseNetworkException("offline") }
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_no_connection))
    composeTestRule.runOnIdle { assertTrue(signedInUsers.isEmpty()) }
  }

  @Test
  fun retryAfterAnError_signsInAgainAndCallsOnSignedIn() {
    repository.onSignIn = { throw FirebaseNetworkException("offline") }
    setScreen()
    clickSignIn()
    waitForText(string(R.string.sign_in_retry))

    repository.onSignIn = { user }
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { signedInUsers.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule
        .onNodeWithText(string(R.string.sign_in_error_no_connection))
        .assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(2, provider.tokenRequests.size)
      assertEquals(listOf("google-id-token", "google-id-token"), repository.signInTokens)
      assertEquals(listOf(user), signedInUsers)
    }
  }
}
