// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
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
            FakePlanningRepository(),
            Clock.fixed(day.atStartOfDay(zone).toInstant(), zone),
            zone,
            SavedStateHandle(),
            FakeAuthRepository(),
        )
    fun show() = compose.runOnUiThread {
      compose.activity.setContent {
        OncompanionTheme {
          PlanningScreen(
              NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext())),
              vm,
              onOpenAppointment = {},
              onOpenEvent = {},
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
}
