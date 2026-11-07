// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.auth

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
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
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.StartViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AccountCheckScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun accountNotChecked_explainsIt_andRetries() {
    var retries = 0
    composeTestRule.setContent { AccountCheckContent(isChecking = false, onRetry = { retries++ }) }

    composeTestRule
        .onNodeWithText(context.getString(R.string.account_check_title))
        .assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.account_check_body))
        .assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.account_check_loading).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).performClick()

    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun checking_showsOnlyTheSpinner() {
    composeTestRule.setContent { AccountCheckContent(isChecking = true, onRetry = {}) }

    composeTestRule.onNodeWithTag(C.Tag.account_check_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).assertDoesNotExist()
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun retry_isFullyVisibleInLandscape() {
    composeTestRule.setContent { AccountCheckContent(isChecking = false, onRetry = {}) }
    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).assertIsFullyVisible()
  }

  @Test
  fun screen_retryOnceTheProfileIsKnown_startsWhereItSays() {
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "anne") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val profiles =
        FakeUserProfileRepository().apply {
          seed(UserProfile("anne", Role.PATIENT, firstName = "Anne"))
        }
    val viewModel = StartViewModel(auth, profiles, decided = Route.ACCOUNT_CHECK)
    val started = mutableListOf<String>()
    composeTestRule.setContent {
      AccountCheckScreen(viewModel = viewModel, onChecked = { started += it })
    }

    composeTestRule.onNodeWithTag(C.Tag.account_check_retry).performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000) { started.isNotEmpty() }
    assertEquals(listOf(Route.OVERVIEW), started)
  }
}
