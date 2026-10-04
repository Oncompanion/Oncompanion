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
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
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
  private val profiles = FakeUserProfileRepository()
  private val provider = FakeGoogleCredentialProvider(onGetToken = { "google-id-token" })
  private val destinations = mutableListOf<AfterSignIn>()
  private lateinit var screenContext: Context

  private fun setScreen() {
    val viewModel = SignInViewModel(repository, profiles)
    composeTestRule.setContent {
      screenContext = LocalContext.current
      SignInScreen(
          onSignedIn = { destinations += it },
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
      assertTrue(destinations.isEmpty())
    }
  }

  @Test
  fun newUser_usesTheProviderTokenAndCallsOnSignedInOnceWithOnboarding() {
    setScreen()
    clickSignIn()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { destinations.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle {
      assertEquals(1, provider.tokenRequests.size)
      assertSame(screenContext, provider.tokenRequests.single())
      assertEquals(listOf("google-id-token"), repository.signInTokens)
      assertEquals(listOf(AfterSignIn.ONBOARDING), destinations)
    }
  }

  @Test
  fun returningUser_callsOnSignedInOnceWithOverview() {
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    setScreen()
    clickSignIn()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { destinations.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { assertEquals(listOf(AfterSignIn.OVERVIEW), destinations) }
  }

  @Test
  fun profileCheckOffline_showsTheNoConnectionSnackbarAndDoesNotCallOnSignedIn() {
    profiles.getProfileError =
        FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_no_connection))
    composeTestRule.onNodeWithTag(C.Tag.google_sign_in_button).assertIsEnabled()
    composeTestRule.runOnIdle {
      assertEquals(listOf("google-id-token"), repository.signInTokens)
      assertTrue(destinations.isEmpty())
    }
  }

  @Test
  fun profileCheckFailure_showsTheFailedSnackbarAndDoesNotCallOnSignedIn() {
    profiles.getProfileError =
        FirebaseFirestoreException("denied", FirebaseFirestoreException.Code.PERMISSION_DENIED)
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_failed))
    composeTestRule.runOnIdle { assertTrue(destinations.isEmpty()) }
  }

  @Test
  fun retryAfterAProfileCheckFailure_callsOnSignedIn() {
    profiles.getProfileError =
        FirebaseFirestoreException("offline", FirebaseFirestoreException.Code.UNAVAILABLE)
    setScreen()
    clickSignIn()
    waitForText(string(R.string.sign_in_retry))

    profiles.getProfileError = null
    profiles.seed(UserProfile(uid = user.uid, role = Role.PATIENT, firstName = "Alex"))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { destinations.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule.runOnIdle { assertEquals(listOf(AfterSignIn.OVERVIEW), destinations) }
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
    composeTestRule.waitUntil(timeoutMillis = 5_000) { destinations.isNotEmpty() }
    composeTestRule.runOnIdle {
      assertEquals(listOf("late-token"), repository.signInTokens)
      assertEquals(listOf(AfterSignIn.ONBOARDING), destinations)
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
      assertTrue(destinations.isEmpty())
    }
  }

  @Test
  fun noGoogleAccount_showsItsSnackbar() {
    provider.onGetToken = { throw GoogleSignInException(GoogleSignInException.Reason.NO_ACCOUNT) }
    setScreen()

    clickSignIn()

    waitForText(string(R.string.sign_in_error_no_google_account))
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).assertIsDisplayed()
    composeTestRule.runOnIdle { assertTrue(destinations.isEmpty()) }
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
    composeTestRule.runOnIdle { assertTrue(destinations.isEmpty()) }
  }

  @Test
  fun retryAfterAnError_signsInAgainAndCallsOnSignedIn() {
    repository.onSignIn = { throw FirebaseNetworkException("offline") }
    setScreen()
    clickSignIn()
    waitForText(string(R.string.sign_in_retry))

    repository.onSignIn = { user }
    composeTestRule.onNodeWithText(string(R.string.sign_in_retry)).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { destinations.isNotEmpty() }
    composeTestRule.waitForIdle()
    composeTestRule
        .onNodeWithText(string(R.string.sign_in_error_no_connection))
        .assertDoesNotExist()
    composeTestRule.runOnIdle {
      assertEquals(2, provider.tokenRequests.size)
      assertEquals(listOf("google-id-token", "google-id-token"), repository.signInTokens)
      assertEquals(listOf(AfterSignIn.ONBOARDING), destinations)
    }
  }
}
