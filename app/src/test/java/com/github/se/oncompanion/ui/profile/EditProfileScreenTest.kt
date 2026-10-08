package com.github.se.oncompanion.ui.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditProfileScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private var state by
      mutableStateOf(EditProfileUiState(EditProfileStatus.READY, "Alex", "Doe", "Lymphoma"))
  private var backs = 0
  private var retries = 0
  private var saves = 0

  private fun content(initial: EditProfileUiState = state) {
    state = initial
    compose.setContent {
      EditProfileContent(
          state,
          onBack = { backs++ },
          onRetry = { retries++ },
          onFirstNameChange = { state = state.copy(firstName = it) },
          onFamilyNameChange = { state = state.copy(familyName = it) },
          onCancerTypeChange = { state = state.copy(cancerType = it) },
          onSave = { saves++ },
      )
    }
  }

  private fun node(tag: String) = compose.onNodeWithTag(tag)

  @Test
  fun readyPrefillsFieldsAndDispatchesAllEdits() {
    content()
    node(C.Tag.edit_profile_first_name).assertTextContains("Alex").performTextReplacement("Sam")
    node(C.Tag.edit_profile_family_name).assertTextContains("Doe").performTextReplacement("Moreau")
    node(C.Tag.cancer_type_field).assertTextContains("Lymphoma").performTextReplacement("Free text")
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals("Sam", state.firstName)
      assertEquals("Moreau", state.familyName)
      assertEquals("Free text", state.cancerType)
      assertEquals(1, saves)
    }
  }

  @Test
  fun emptyOptionalFieldsAreEditableAndBlankFirstNameShowsValidation() {
    content(EditProfileUiState(EditProfileStatus.READY, "Alex"))
    node(C.Tag.edit_profile_family_name).assertTextContains("")
    node(C.Tag.cancer_type_field).assertTextContains("")
    node(C.Tag.edit_profile_first_name).performTextReplacement("  ")
    compose.onNodeWithText("Please enter your first name").assertExists()
    node(C.Tag.edit_profile_save).performScrollTo().assertIsNotEnabled()
  }

  @Test
  fun suggestionsCanBeSelectedAndOptionalFieldsCleared() {
    content()
    node(C.Tag.cancer_type_field).performTextReplacement("bre")
    compose.onAllNodesWithTag(C.Tag.cancer_type_suggestion)[0].performClick()
    compose.runOnIdle { assertTrue(state.cancerType.contains("Breast", ignoreCase = true)) }
    node(C.Tag.cancer_type_field).performTextClearance()
    node(C.Tag.edit_profile_family_name).performTextClearance()
    compose.runOnIdle {
      assertEquals("", state.cancerType)
      assertEquals("", state.familyName)
    }
  }

  @Test
  fun loadingAndUnavailableStatesDoNotShowForm() {
    content(EditProfileUiState())
    node(C.Tag.edit_profile_loading).assertIsDisplayed()
    node(C.Tag.edit_profile_first_name).assertDoesNotExist()
    compose.runOnIdle { state = EditProfileUiState(EditProfileStatus.SIGNED_OUT) }
    node(C.Tag.edit_profile_unavailable).assertIsDisplayed()
    compose.onNodeWithText("Sign in to view your profile.").assertIsDisplayed()
    compose.runOnIdle { state = EditProfileUiState(EditProfileStatus.MISSING_PROFILE) }
    compose.onNodeWithText("Your profile is not available yet.").assertIsDisplayed()
  }

  @Test
  fun loadErrorOffersRetry() {
    content(EditProfileUiState(EditProfileStatus.ERROR))
    node(C.Tag.edit_profile_load_error).assertIsDisplayed()
    node(C.Tag.edit_profile_retry).performClick()
    compose.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun savingDisablesInputsSaveAndAllWaysBack() {
    content(state.copy(isSaving = true))
    node(C.Tag.edit_profile_first_name).assertIsNotEnabled()
    node(C.Tag.edit_profile_family_name).assertIsNotEnabled()
    node(C.Tag.cancer_type_field).assertIsNotEnabled()
    node(C.Tag.edit_profile_back).assertIsNotEnabled()
    node(C.Tag.edit_profile_save).performScrollTo().assertIsNotEnabled()
    node(C.Tag.edit_profile_saving).assertIsDisplayed()
    node(C.Tag.edit_profile_cancel).performScrollTo().assertIsNotEnabled()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(0, backs) }
  }

  @Test
  fun saveErrorRetainsFormAndAllowsRetry() {
    content(state.copy(saveFailed = true))
    node(C.Tag.edit_profile_save_error).performScrollTo().assertIsDisplayed()
    node(C.Tag.edit_profile_save).assertIsEnabled().performClick()
    compose.runOnIdle { assertEquals(1, saves) }
  }

  @Test
  fun cancelToolbarAndSystemBackCallSameCallback() {
    content()
    node(C.Tag.edit_profile_back).performClick()
    node(C.Tag.edit_profile_cancel).performScrollTo().performClick()
    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    compose.runOnIdle { assertEquals(3, backs) }
  }

  private fun model(): Pair<EditProfileViewModel, FakeUserProfileRepository> {
    val auth = FakeAuthRepository { AuthUser("uid-1") }
    runBlocking { auth.signInWithGoogle("token") }
    val profiles =
        FakeUserProfileRepository().apply {
          seed(UserProfile("uid-1", Role.PATIENT, "Alex", "Doe", "Lymphoma"))
        }
    return EditProfileViewModel(auth, profiles) to profiles
  }

  @Test
  fun screenSaveUpdatesProfileAndCompletesOnce() {
    val (vm, profiles) = model()
    var completions = 0
    compose.setContent {
      EditProfileScreen(onBack = {}, onSaved = { completions++ }, viewModel = vm)
    }
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    node(C.Tag.edit_profile_first_name).performTextReplacement("  Sam  ")
    node(C.Tag.edit_profile_family_name).performTextClearance()
    node(C.Tag.cancer_type_field).performTextClearance()
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { completions == 1 }
    compose.runOnIdle {
      assertEquals("Sam", profiles.profiles["uid-1"]!!.firstName)
      assertNull(profiles.profiles["uid-1"]!!.familyName)
      assertNull(profiles.profiles["uid-1"]!!.cancerType)
      assertFalse(vm.uiState.value.isSaved)
    }
    compose.waitForIdle()
    compose.runOnIdle { assertEquals(1, completions) }
  }

  @Test
  fun screenCancelDoesNotWriteDraft() {
    val (vm, profiles) = model()
    compose.setContent {
      EditProfileScreen(onBack = { backs++ }, onSaved = { fail("Must not save") }, viewModel = vm)
    }
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    node(C.Tag.edit_profile_first_name).performTextReplacement("Unsaved")
    node(C.Tag.edit_profile_cancel).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, backs)
      assertEquals("Alex", profiles.profiles["uid-1"]!!.firstName)
    }
  }

  @Test
  fun screenSaveFailureCanRetryWithoutLosingDraft() {
    val (vm, profiles) = model()
    profiles.writeError = IllegalStateException("failure")
    var completions = 0
    compose.setContent {
      EditProfileScreen(onBack = {}, onSaved = { completions++ }, viewModel = vm)
    }
    compose.waitUntil(5000) { vm.uiState.value.status == EditProfileStatus.READY }
    node(C.Tag.edit_profile_first_name).performTextReplacement("Draft")
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { vm.uiState.value.saveFailed }
    node(C.Tag.edit_profile_save_error).performScrollTo().assertIsDisplayed()
    node(C.Tag.edit_profile_first_name).performScrollTo().assertTextContains("Draft")
    compose.runOnIdle {
      profiles.writeError = null
      assertEquals(0, completions)
    }
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    compose.waitUntil(5000) { completions == 1 }
  }

  @Test
  fun profileEditSaveReturnsToUpdatedProfile_andCancelReopensWithStoredValues() {
    val auth = FakeAuthRepository { AuthUser("uid-1") }
    runBlocking { auth.signInWithGoogle("token") }
    val profiles =
        FakeUserProfileRepository().apply { seed(UserProfile("uid-1", Role.PATIENT, "Alex")) }
    lateinit var nav: TestNavHostController
    compose.setContent {
      val context = LocalContext.current
      nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      val actions = remember(nav) { NavigationActions(nav) }
      NavHost(navController = nav, startDestination = Screen.PROFILE) {
        composable(Screen.PROFILE) {
          ProfileScreen(
              onBack = actions::goBack,
              onEdit = { actions.navigateTo(Screen.EDIT_PROFILE) },
              onSignedOut = {},
              viewModel = viewModel { ProfileViewModel(auth, profiles) },
          )
        }
        composable(Screen.EDIT_PROFILE) {
          EditProfileScreen(
              onBack = actions::goBack,
              onSaved = actions::goBack,
              viewModel = viewModel { EditProfileViewModel(auth, profiles) },
          )
        }
      }
    }
    node(C.Tag.profile_edit).performClick()
    node(C.Tag.edit_profile_first_name).performTextReplacement("Sam")
    node(C.Tag.edit_profile_save).performScrollTo().performClick()
    node(C.Tag.profile_name).assertTextEquals("Sam")
    compose.runOnIdle { assertEquals(Screen.PROFILE, nav.currentDestination?.route) }
    node(C.Tag.profile_edit).performClick()
    node(C.Tag.edit_profile_first_name).performTextReplacement("Discard")
    node(C.Tag.edit_profile_cancel).performScrollTo().performClick()
    node(C.Tag.profile_name).assertTextEquals("Sam")
    node(C.Tag.profile_edit).performClick()
    node(C.Tag.edit_profile_first_name).assertTextContains("Sam")
    node(C.Tag.edit_profile_back).performClick()
    compose.runOnIdle { assertEquals(Screen.PROFILE, nav.currentDestination?.route) }
  }
}
