package com.github.se.oncompanion.ui.profile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.auth.AuthRepository
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private class ProfileScreenAuthRepository(private val user: AuthUser) : AuthRepository {
  override val currentUser: AuthUser?
    get() = user

  override fun observeCurrentUser(): Flow<AuthUser?> = flowOf(user)

  override suspend fun signInWithGoogle(idToken: String): AuthUser = error("unused")

  override fun signOut() = Unit
}

@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun setContent(
      state: ProfileUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      ProfileContent(uiState = state, onBack = onBack, onRetry = onRetry)
    }
  }

  @Test
  fun loadingState_showsProfileTitleAndProgress() {
    setContent(ProfileUiState.Loading)

    composeTestRule.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText("My profile").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_loading).assertIsDisplayed()
  }

  @Test
  fun signedOutState_explainsProfileRequiresSignIn() {
    setContent(ProfileUiState.SignedOut)

    composeTestRule.onNodeWithTag(C.Tag.profile_signed_out).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign in to view your profile.").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_information).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.profile_edit).assertDoesNotExist()
  }

  @Test
  fun missingProfileState_isDifferentFromLoadingAndError() {
    setContent(ProfileUiState.MissingProfile)

    composeTestRule.onNodeWithTag(C.Tag.profile_missing).assertIsDisplayed()
    composeTestRule.onNodeWithText("Your profile is not available yet.").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_loading).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.profile_error).assertDoesNotExist()
  }

  @Test
  fun errorState_showsRetry_andCallsTheCallback() {
    var retries = 0
    setContent(ProfileUiState.Error, onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.profile_error).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_retry).performClick()
    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun loadedProfile_displaysNameEmailMemberSinceAndCancerType_withoutRole() {
    setContent(
        ProfileUiState.Loaded(
            ProfileDetails(
                firstName = "Alex",
                familyName = "Moreau",
                cancerType = "Breast cancer",
                email = "alex@example.com",
                memberSince = Instant.parse("2026-09-04T12:00:00Z"),
            )
        )
    )

    composeTestRule.onNodeWithTag(C.Tag.profile_header).assertIsDisplayed()
    composeTestRule.onNodeWithText("A").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_name).assertTextEquals("Alex Moreau")
    composeTestRule
        .onNodeWithTag(C.Tag.profile_member_since)
        .assertTextEquals("Oncompanion member since Sep 2026")
    composeTestRule.onNodeWithTag(C.Tag.profile_information).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_first_name).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_family_name).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_email).assertIsDisplayed()
    composeTestRule.onNodeWithText("Email").assertIsDisplayed()
    composeTestRule.onNodeWithText("alex@example.com").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_cancer_type).assertIsDisplayed()
    composeTestRule.onNodeWithText("Cancer type").assertIsDisplayed()
    composeTestRule.onNodeWithText("Breast cancer").assertIsDisplayed()
    composeTestRule.onNodeWithText("PATIENT").assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.profile_edit).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign out").assertDoesNotExist()
  }

  @Test
  fun absentOptionalProfileValues_areNotShown() {
    setContent(
        ProfileUiState.Loaded(
            ProfileDetails(
                firstName = "Alex",
                familyName = null,
                cancerType = null,
                email = null,
                memberSince = null,
            )
        )
    )

    composeTestRule.onNodeWithTag(C.Tag.profile_name).assertTextEquals("Alex")
    composeTestRule.onNodeWithTag(C.Tag.profile_member_since).assertTextEquals("Oncompanion member")
    composeTestRule.onNodeWithTag(C.Tag.profile_first_name).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.profile_family_name).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.profile_email).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.profile_cancer_type).assertDoesNotExist()
  }

  @Test
  fun screen_displaysTheViewModelProfile() {
    val user = AuthUser(uid = "uid-1", email = "alex@example.com")
    val profiles =
        FakeUserProfileRepository().apply {
          seed(
              UserProfile(
                  uid = user.uid,
                  role = Role.PATIENT,
                  firstName = "Alex",
                  familyName = "Moreau",
                  cancerType = "Breast cancer",
              )
          )
        }
    val viewModel = ProfileViewModel(ProfileScreenAuthRepository(user), profiles)

    composeTestRule.setContent { ProfileScreen(onBack = {}, viewModel = viewModel) }

    composeTestRule.onNodeWithTag(C.Tag.profile_screen).assertIsDisplayed()
    composeTestRule.onNodeWithText("Alex Moreau").assertIsDisplayed()
    composeTestRule.onNodeWithText("alex@example.com").assertIsDisplayed()
    composeTestRule.onNodeWithText("Breast cancer").assertIsDisplayed()
  }

  @Test
  fun memberSince_usesSystemTimezone() {
    val previousTimezone = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
    try {
      setContent(
          ProfileUiState.Loaded(
              ProfileDetails(
                  firstName = "Alex",
                  familyName = null,
                  cancerType = null,
                  email = null,
                  memberSince = Instant.parse("2026-09-01T00:30:00Z"),
              )
          )
      )

      composeTestRule
          .onNodeWithTag(C.Tag.profile_member_since)
          .assertTextEquals("Oncompanion member since Aug 2026")
    } finally {
      TimeZone.setDefault(previousTimezone)
    }
  }

  @Test
  fun backArrow_callsTheProvidedCallback() {
    var backCount = 0
    setContent(ProfileUiState.SignedOut, onBack = { backCount++ })

    composeTestRule.onNodeWithTag(C.Tag.profile_back).performClick()

    composeTestRule.runOnIdle { assertEquals(1, backCount) }
  }

  @Test
  fun editAction_callsCallbackOnlyForLoadedProfile() {
    var edits = 0
    composeTestRule.setContent {
      ProfileContent(
          ProfileUiState.Loaded(ProfileDetails("Alex", null, null, null, null)),
          onBack = {},
          onRetry = {},
          onEdit = { edits++ },
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.profile_edit).performClick()
    composeTestRule.runOnIdle { assertEquals(1, edits) }
  }
}
