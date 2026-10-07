package com.github.se.oncompanion.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real app graph to verify Login replaces both active and saved authenticated history. */
@RunWith(AndroidJUnit4::class)
class SignOutNavigationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val auth = FakeAuthRepository()
  private val profiles = FakeUserProfileRepository()
  private val credentials = FakeGoogleCredentialProvider()
  private lateinit var nav: TestNavHostController
  private lateinit var actions: NavigationActions

  private class CachedTabViewModel : ViewModel() {
    var cleared = false

    override fun onCleared() {
      cleared = true
    }
  }

  private fun content() {
    val user = runBlocking { auth.signInWithGoogle("one") }
    profiles.seed(UserProfile(user.uid, Role.PATIENT, "Alex", cancerType = "Private diagnosis"))
    compose.setContent {
      val context = LocalContext.current
      nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      AppNavHost(
          nav,
          Route.OVERVIEW,
          authRepository = auth,
          profileRepository = profiles,
          credentialProvider = credentials,
      )
    }
    compose.runOnIdle { actions = NavigationActions(nav) }
  }

  private fun signOut() {
    compose.onNodeWithTag(C.Tag.overview_shortcut_profile).performClick()
    compose.onNodeWithTag(C.Tag.profile_cancer_type).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_sign_out).performClick()
    compose.onNodeWithTag(C.Tag.profile_sign_out_confirm).performClick()
    compose.onNodeWithTag(C.Tag.sign_in_screen).assertIsDisplayed()
  }

  @Test
  fun confirmedSignOutOpensLoginAndBackCannotRevealCachedProfileOrOverview() {
    content()
    signOut()
    compose.onNodeWithTag(C.Tag.profile_screen).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.overview_screen).assertDoesNotExist()
    compose.onNodeWithText("Private diagnosis").assertDoesNotExist()
    compose.runOnIdle {
      assertNull(auth.currentUser)
      assertEquals(1, auth.signOutCalls)
      assertEquals(1, credentials.clearRequests.size)
      assertNull(nav.previousBackStackEntry)
      assertFalse(
          nav.backStack.any {
            it.destination.route == Screen.PROFILE || it.destination.route == Screen.OVERVIEW
          }
      )
      actions.goBack()
      assertEquals(Screen.SIGN_IN, actions.currentRoute())
      // NavHost has no previous destination to handle system Back, so Android may exit Login.
      compose.activity.onBackPressedDispatcher.onBackPressed()
      assertFalse(
          nav.backStack.any {
            it.destination.route == Screen.PROFILE || it.destination.route == Screen.OVERVIEW
          }
      )
    }
  }

  @Test
  fun firebaseSignOutFailureKeepsProfileAndNeverOpensLogin() {
    content()
    compose.onNodeWithTag(C.Tag.overview_shortcut_profile).performClick()
    compose.runOnIdle { auth.signOutError = IllegalStateException("Immediate failure") }
    compose.onNodeWithTag(C.Tag.profile_sign_out).performClick()
    compose.onNodeWithTag(C.Tag.profile_sign_out_confirm).performClick()
    compose.onNodeWithTag(C.Tag.profile_sign_out_error).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.sign_in_screen).assertDoesNotExist()
    compose.runOnIdle {
      assertEquals(Screen.PROFILE, actions.currentRoute())
      assertNotNull(auth.currentUser)
      assertTrue(credentials.clearRequests.isEmpty())
    }
    compose.onNodeWithTag(C.Tag.profile_sign_out_cancel).performClick()
    compose.onNodeWithTag(C.Tag.profile_cancer_type).assertIsDisplayed()
  }

  @Test
  fun signOutDestroysSavedTabViewModelsAndAnotherSessionCannotRestoreThem() {
    content()
    lateinit var cached: CachedTabViewModel
    compose.runOnIdle { actions.navigateToTab(Route.PLANNING) }
    compose.runOnIdle {
      cached =
          ViewModelProvider(
              nav.getBackStackEntry(Screen.PLANNING),
              object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CachedTabViewModel() as T
              },
          )[CachedTabViewModel::class.java]
      actions.navigateToTab(Route.EVENTS)
    }
    compose.runOnIdle { actions.navigateToTab(Route.OVERVIEW) }
    compose.runOnIdle { assertFalse(cached.cleared) }
    signOut()
    compose.runOnIdle {
      assertTrue("Saved Planning ViewModel still retains the old session", cached.cleared)
      runBlocking { auth.signInWithGoogle("two") }
      actions.navigateAndClearBackStack(Route.OVERVIEW)
      actions.navigateToTab(Route.PLANNING)
    }
    compose.runOnIdle {
      val entry = nav.getBackStackEntry(Screen.PLANNING)
      val fresh =
          ViewModelProvider(
              entry,
              object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CachedTabViewModel() as T
              },
          )[CachedTabViewModel::class.java]
      assertNotSame(cached, fresh)
      assertFalse(fresh.cleared)
    }
    compose.runOnIdle { actions.navigateTo(Screen.PROFILE) }
    compose.onNodeWithTag(C.Tag.profile_missing).assertIsDisplayed()
    compose.onNodeWithText("Private diagnosis").assertDoesNotExist()
  }
}
