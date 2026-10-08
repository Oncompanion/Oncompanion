package com.github.se.oncompanion.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Confirmation, cancellation, errors and in-flight controls use the existing Profile styling. */
@RunWith(AndroidJUnit4::class)
class ProfileSignOutScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val loaded = ProfileUiState.Loaded(ProfileDetails("Alex", null, "Private", null, null))

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  @Test
  fun contentDispatchesRequestCancelAndConfirmWithResourceLabels() {
    val state = mutableStateOf<ProfileUiState>(loaded)
    var requests = 0
    var cancels = 0
    var confirms = 0
    compose.setContent {
      ProfileContent(
          state.value,
          onBack = {},
          onRetry = {},
          onEdit = {},
          onSignOut = {
            requests++
            state.value = ProfileUiState.ConfirmingSignOut(loaded)
          },
          onCancelSignOut = {
            cancels++
            state.value = loaded
          },
          onConfirmSignOut = { confirms++ },
      )
    }
    node(C.Tag.profile_sign_out).assertIsDisplayed().performClick()
    node(C.Tag.profile_sign_out_dialog).assertIsDisplayed()
    compose.onNodeWithText("Sign out?").assertIsDisplayed()
    compose
        .onNodeWithText("You will need to sign in again to access your Oncompanion data.")
        .assertIsDisplayed()
    node(C.Tag.profile_sign_out_cancel).performClick()
    node(C.Tag.profile_sign_out_dialog).assertDoesNotExist()
    node(C.Tag.profile_sign_out).performClick()
    node(C.Tag.profile_sign_out_confirm).performClick()
    compose.runOnIdle {
      assertEquals(2, requests)
      assertEquals(1, cancels)
      assertEquals(1, confirms)
    }
  }

  @Test
  fun dialogErrorAndSavingStatesKeepControlsConsistent() {
    val state =
        mutableStateOf<ProfileUiState>(ProfileUiState.ConfirmingSignOut(loaded, failed = true))
    var cancels = 0
    compose.setContent {
      ProfileContent(
          state.value,
          onBack = {},
          onRetry = {},
          onEdit = {},
          onSignOut = {},
          onCancelSignOut = { cancels++ },
          onConfirmSignOut = {},
      )
    }
    node(C.Tag.profile_sign_out_error).assertIsDisplayed()
    node(C.Tag.profile_sign_out_confirm).assertIsEnabled()
    compose.runOnIdle {
      state.value = ProfileUiState.ConfirmingSignOut(ProfileUiState.SignedOut, true)
    }
    node(C.Tag.profile_sign_out_error).assertDoesNotExist()
    node(C.Tag.profile_signing_out).assertIsDisplayed()
    node(C.Tag.profile_sign_out_confirm).assertIsNotEnabled()
    node(C.Tag.profile_sign_out_cancel).assertIsNotEnabled()
    node(C.Tag.profile_name).assertDoesNotExist()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(0, cancels) }
  }

  @Test
  fun signedOutAndCompletionHaveNoSignOutActionOrPrivateDetails() {
    val state = mutableStateOf<ProfileUiState>(ProfileUiState.SignedOut)
    compose.setContent {
      ProfileContent(
          state.value,
          onBack = {},
          onRetry = {},
          onEdit = {},
          onSignOut = {},
          onCancelSignOut = {},
          onConfirmSignOut = {},
      )
    }
    node(C.Tag.profile_sign_out).assertDoesNotExist()
    node(C.Tag.profile_name).assertDoesNotExist()
    compose.runOnIdle { state.value = ProfileUiState.SignOutComplete }
    node(C.Tag.profile_sign_out).assertDoesNotExist()
    node(C.Tag.profile_name).assertDoesNotExist()
  }

  @Test
  fun systemBackDismissesConfirmationWithoutSigningOut() {
    val auth = FakeAuthRepository()
    val user = runBlocking { auth.signInWithGoogle("one") }
    val profiles =
        FakeUserProfileRepository().apply { seed(UserProfile(user.uid, Role.PATIENT, "Alex")) }
    val vm = ProfileViewModel(auth, profiles)
    compose.setContent {
      ProfileScreen(
          onBack = {},
          onEdit = {},
          onSignedOut = { fail("Must stay signed in") },
          viewModel = vm,
          credentialProvider = FakeGoogleCredentialProvider(),
      )
    }
    node(C.Tag.profile_sign_out).performClick()
    compose.runOnIdle {
      // The modal window has its own Back dispatcher, separate from the host Activity.
      (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog)
          .onBackPressedDispatcher
          .onBackPressed()
    }
    node(C.Tag.profile_sign_out_dialog).assertDoesNotExist()
    compose.runOnIdle {
      assertNotNull(auth.currentUser)
      assertEquals(0, auth.signOutCalls)
    }
  }

  @Test
  fun screenCancelKeepsSessionAndConfirmClearsCredentialsAndCompletesOnce() {
    val auth = FakeAuthRepository()
    val user = runBlocking { auth.signInWithGoogle("one") }
    val profiles =
        FakeUserProfileRepository().apply { seed(UserProfile(user.uid, Role.PATIENT, "Alex")) }
    val vm = ProfileViewModel(auth, profiles)
    val gate = CompletableDeferred<Unit>()
    val provider = FakeGoogleCredentialProvider(onClear = { gate.await() })
    var completions = 0
    compose.setContent {
      ProfileScreen(
          onBack = {},
          onEdit = {},
          onSignedOut = { completions++ },
          viewModel = vm,
          credentialProvider = provider,
      )
    }
    node(C.Tag.profile_sign_out).performClick()
    node(C.Tag.profile_sign_out_cancel).performClick()
    compose.runOnIdle {
      assertNotNull(auth.currentUser)
      assertEquals(0, auth.signOutCalls)
    }
    node(C.Tag.profile_sign_out).performClick()
    node(C.Tag.profile_sign_out_confirm).performClick()
    node(C.Tag.profile_sign_out_confirm).assertIsNotEnabled()
    compose.runOnIdle {
      assertNull(auth.currentUser)
      assertEquals(1, auth.signOutCalls)
      assertEquals(1, provider.clearRequests.size)
      assertEquals(0, completions)
      gate.complete(Unit)
    }
    compose.waitForIdle()
    compose.runOnIdle { assertEquals(1, completions) }
    node(C.Tag.profile_name).assertDoesNotExist()
  }
}
