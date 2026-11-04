// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies that Profile details and actions share the short landscape viewport. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LANDSCAPE_PHONE)
class ProfileLandscapeTest {
  @get:Rule val compose = createComposeRule()
  private val loaded =
      ProfileUiState.Loaded(
          ProfileDetails(
              "Alex",
              "Rivera",
              "Recorded cancer type",
              "alex@example.com",
              Instant.parse("2026-10-01T12:00:00Z"),
              "Recorded hospital and oncology department",
          )
      )

  /** The toolbar scrolls away, giving full fields and actions room to be reached. */
  @Test fun landscapeReclaimsToolbarAndActionSpace() = checkScrollableProfile()

  /** The same behavior works at the smallest supported landscape test size. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscapeReclaimsToolbarAndActionSpace() = checkScrollableProfile()

  private fun checkScrollableProfile() {
    var edits = 0
    var signOuts = 0
    var backs = 0
    compose.setContent {
      OncompanionTheme {
        ProfileContent(loaded, { backs++ }, {}, { signOuts++ }, {}, {}, { edits++ })
      }
    }
    compose.onNodeWithTag(C.Tag.profile_cancer_type).performScrollTo().assertIsFullyVisible()
    compose
        .onNodeWithTag(C.Tag.profile_sign_out)
        .performScrollTo()
        .also { it.assertIsFullyVisible() }
        .performClick()
    // The old layout could reach the final field but never released its pinned toolbar space.
    compose.onNodeWithTag(C.Tag.profile_back).assertIsNotDisplayed()
    compose
        .onNodeWithTag(C.Tag.profile_edit)
        .performScrollTo()
        .also { it.assertIsFullyVisible() }
        .performClick()
    compose
        .onNodeWithTag(C.Tag.profile_back)
        .performScrollTo()
        .also { it.assertIsFullyVisible() }
        .performClick()
    assertEquals(listOf(1, 1, 1), listOf(edits, signOuts, backs))
  }

  /** Scroll survives composition restoration without resetting the user to the top. */
  @Test
  fun compactProfileRestoresScrollPosition() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent { OncompanionTheme { ProfileContent(loaded, {}, {}, {}, {}, {}, {}) } }
    compose.onNodeWithTag(C.Tag.profile_sign_out).performScrollTo().assertIsFullyVisible()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(C.Tag.profile_sign_out).assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.profile_back).assertIsNotDisplayed()
  }

  /** Loading and all message states keep their available actions reachable. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun feedbackStatesFitTheCompactViewport() {
    var current: ProfileUiState by mutableStateOf(loaded)
    var retries = 0
    compose.setContent {
      OncompanionTheme { ProfileContent(current, {}, { retries++ }, {}, {}, {}, {}) }
    }
    for ((value, tag) in
        listOf(
            ProfileUiState.Loading to C.Tag.profile_loading,
            ProfileUiState.Error to C.Tag.profile_error,
            ProfileUiState.MissingProfile to C.Tag.profile_missing,
            ProfileUiState.SignedOut to C.Tag.profile_signed_out,
        )) {
      compose.runOnIdle { current = value }
      compose.onNodeWithTag(tag).performScrollTo().assertIsFullyVisible()
      if (value == ProfileUiState.Error)
          compose
              .onNodeWithTag(C.Tag.profile_retry)
              .performScrollTo()
              .also { it.assertIsFullyVisible() }
              .performClick()
      if (value != ProfileUiState.SignedOut)
          compose.onNodeWithTag(C.Tag.profile_sign_out).performScrollTo().assertIsFullyVisible()
      compose.onNodeWithTag(C.Tag.profile_back).performScrollTo().assertIsFullyVisible()
    }
    assertEquals(1, retries)
  }

  /** The confirmation dialog also fits the standard landscape window. */
  @Test fun landscapeSignOutConfirmationActionsRemainReachable() = checkConfirmationActions()

  /** The confirmation dialog retains fully visible actions on a small landscape screen. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun signOutConfirmationActionsRemainReachable() = checkConfirmationActions()

  private fun checkConfirmationActions() {
    var current: ProfileUiState by mutableStateOf(ProfileUiState.ConfirmingSignOut(loaded))
    var cancels = 0
    var confirms = 0
    compose.setContent {
      OncompanionTheme { ProfileContent(current, {}, {}, {}, { cancels++ }, { confirms++ }, {}) }
    }
    for (pending in listOf(false, true)) {
      compose.runOnIdle {
        current = ProfileUiState.ConfirmingSignOut(loaded, pendingChanges = pending)
      }
      compose
          .onNodeWithTag(C.Tag.profile_sign_out_cancel)
          .also { it.assertIsFullyVisible() }
          .performClick()
      compose
          .onNodeWithTag(C.Tag.profile_sign_out_confirm)
          .also { it.assertIsFullyVisible() }
          .performClick()
    }
    assertEquals(2, cancels)
    assertEquals(2, confirms)
  }
}
