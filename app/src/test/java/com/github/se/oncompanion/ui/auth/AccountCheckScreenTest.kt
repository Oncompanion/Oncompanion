// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.auth.SignOut
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.session.FakeCleanupJournal
import com.github.se.oncompanion.model.session.FakeLocalSessionRepository
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.model.user.UserProfileRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.StartViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AccountCheckScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun accountNotChecked_explainsIt_retries_orUsesAnotherAccount() {
    var retries = 0
    var switches = 0
    composeTestRule.setContent {
      AccountCheckContent(
          isChecking = false,
          onRetry = { retries++ },
          onUseAnotherAccount = { switches++ },
      )
    }

    composeTestRule
        .onNodeWithText(context.getString(R.string.account_check_title))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.account_check_body))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.account_check_loading).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).performClick()
    composeTestRule.onNodeWithTag(C.Tag.account_check_use_another_account).performClick()

    composeTestRule.runOnIdle {
      assertEquals(1, retries)
      assertEquals(1, switches)
    }
  }

  @Test
  fun checking_showsOnlyTheSpinner() {
    composeTestRule.setContent {
      AccountCheckContent(isChecking = true, onRetry = {}, onUseAnotherAccount = {})
    }

    composeTestRule.onNodeWithTag(C.Tag.account_check_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun bothActions_areReachableInLandscape() {
    composeTestRule.setContent {
      AccountCheckContent(isChecking = false, onRetry = {}, onUseAnotherAccount = {})
    }
    for (tag in listOf(C.Tag.account_check_retry, C.Tag.account_check_use_another_account)) {
      composeTestRule.onNodeWithTag(tag).performScrollTo().assertIsFullyVisible()
    }
  }

  private val auth =
      FakeAuthRepository(onSignIn = { AuthUser(uid = "anne") }).also {
        runBlocking { it.signInWithGoogle("token") }
      }

  /** The screen with its view models; the profile read is [profiles]. */
  private fun setScreen(
      profiles: UserProfileRepository,
      started: MutableList<String> = mutableListOf(),
      signedOut: MutableList<Unit> = mutableListOf(),
  ) {
    val viewModel =
        StartViewModel(
            auth,
            profiles,
            profileTimeoutMillis = 60_000,
            decided = Route.ACCOUNT_CHECK,
        )
    val signOut =
        SignOut(auth, FakeLocalSessionRepository()).apply { initialize(FakeCleanupJournal()) }
    val switchViewModel = AccountSwitchViewModel(signOut)
    composeTestRule.setContent {
      AccountCheckScreen(
          viewModel = viewModel,
          switchViewModel = switchViewModel,
          credentialProvider = FakeGoogleCredentialProvider(),
          onChecked = { started += it },
          onSignedOut = { signedOut += Unit },
      )
    }
  }

  @Test
  fun screen_retry_showsTheSpinner_untilTheProfileAnswers() {
    // A profile read that hasn't answered yet
    val silent =
        object : UserProfileRepository by FakeUserProfileRepository() {
          override fun observeProfile(uid: String): Flow<UserProfile?> = flow {
            awaitCancellation()
          }
        }
    setScreen(silent)

    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).performClick()

    composeTestRule.onNodeWithTag(C.Tag.account_check_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).assertDoesNotExist()
  }

  @Test
  fun screen_useAnotherAccount_signsOut() {
    val signedOut = mutableListOf<Unit>()
    setScreen(FakeUserProfileRepository(), signedOut = signedOut)

    composeTestRule.onNodeWithTag(C.Tag.account_check_use_another_account).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { signedOut.isNotEmpty() }
    assertNull(auth.currentUser)
  }

  @Test
  fun screen_retryOnceTheProfileIsKnown_startsWhereItSays() {
    val profiles =
        FakeUserProfileRepository().apply {
          seed(UserProfile("anne", Role.PATIENT, firstName = "Anne"))
        }
    val started = mutableListOf<String>()
    setScreen(profiles, started = started)

    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { started.isNotEmpty() }
    assertEquals(listOf(Route.OVERVIEW), started)
  }
}
