// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.Role
import com.github.se.oncompanion.model.user.UserProfile
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.auth.FakeGoogleCredentialProvider
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.profile.ProfileScreen
import com.github.se.oncompanion.ui.profile.ProfileViewModel
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Activity recreation retains screen-owned state while the short-window layout is in use. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LANDSCAPE_PHONE)
class LandscapeRotationTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

  /** A chosen week survives recreation with the same ViewModel and still matches the calendar. */
  @Test
  fun planningRetainsTheSelectedDayAcrossActivityRecreation() {
    val day = LocalDate.of(2026, 11, 4)
    val zone = ZoneId.of("Europe/Zurich")
    val vm =
        PlanningViewModel(
            ownPlanning(FakePlanningRepository()),
            Clock.fixed(day.atStartOfDay(zone).toInstant(), zone),
            zone,
            SavedStateHandle(),
        )
    fun show() = compose.runOnUiThread {
      compose.activity.setContent {
        OncompanionTheme {
          PlanningScreen(
              NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext())),
              vm,
              onOpenAppointment = {},
              onOpenEvent = {},
              onOpenMedication = { _, _ -> },
          )
        }
      }
    }
    show()
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.runOnIdle { assertEquals(day.plusWeeks(1), vm.uiState.value.selectedDate) }
    compose.activityRule.scenario.recreate()
    show()
    compose.onNodeWithTag(C.Tag.planningDay(day.plusWeeks(1).toString())).assertIsSelected()
    compose.runOnIdle { assertEquals(day.plusWeeks(1), vm.uiState.value.selectedDate) }
  }

  /** Rotation does not dismiss sign-out confirmation or prematurely navigate out of Profile. */
  @Test
  fun profileRetainsSignOutConfirmationAcrossActivityRecreation() {
    val auth = FakeAuthRepository { AuthUser(it) }
    runTest { auth.signInWithGoogle("patient") }
    val profiles =
        FakeUserProfileRepository().apply { seed(UserProfile("patient", Role.PATIENT, "Alex")) }
    val vm = ProfileViewModel(auth, profiles)
    var signedOut = 0
    fun show() = compose.runOnUiThread {
      compose.activity.setContent {
        OncompanionTheme {
          ProfileScreen(
              {},
              { signedOut++ },
              {},
              viewModel = vm,
              credentialProvider = FakeGoogleCredentialProvider(),
          )
        }
      }
    }
    show()
    compose.onNodeWithTag(C.Tag.profile_sign_out).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertIsDisplayed()
    compose.activityRule.scenario.recreate()
    show()
    compose.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.profile_sign_out_cancel).performClick()
    compose.onNodeWithTag(C.Tag.profile_sign_out_dialog).assertDoesNotExist()
    assertEquals(0, signedOut)
  }
}
