// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.settings.FakeSettingsRepository
import com.github.se.oncompanion.model.settings.SettingsRepositoryProvider
import com.github.se.oncompanion.model.user.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that Settings is reached from Profile and preserves its back stack. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val originalSettings = SettingsRepositoryProvider.repository
  private val originalAuth = AuthRepositoryProvider.repository
  private val originalProfiles = UserProfileRepositoryProvider.repository

  @After
  fun restoreProviders() {
    SettingsRepositoryProvider.repository = originalSettings
    AuthRepositoryProvider.repository = originalAuth
    UserProfileRepositoryProvider.repository = originalProfiles
  }

  @Test
  fun profileSettings_backReturnsToProfile() {
    SettingsRepositoryProvider.repository = FakeSettingsRepository()
    val auth = FakeAuthRepository()
    val user = runBlocking { auth.signInWithGoogle("settings") }
    AuthRepositoryProvider.repository = auth
    UserProfileRepositoryProvider.repository =
        FakeUserProfileRepository().apply { seed(UserProfile(user.uid, Role.PATIENT, "Alex")) }
    lateinit var nav: TestNavHostController
    compose.setContent {
      val context = LocalContext.current
      nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      OncompanionTheme {
        AppNavHost(
            navController = nav,
            startRoute = Route.PROFILE,
            credentialProvider = FakeGoogleCredentialProvider(),
        )
      }
    }
    compose.onNodeWithTag(C.Tag.profile_settings).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.appearance_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.APPEARANCE, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.settings_appearance).performClick()
    compose.onNodeWithTag(C.Tag.appearance_screen).assertIsDisplayed()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.settings_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.SETTINGS, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.settings_back).performClick()
    compose.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.PROFILE, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.profile_settings).performScrollTo().performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
  }
}
