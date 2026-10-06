package com.github.se.oncompanion.ui.planning

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.planning.PlanningRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlanningScreenIntegrationTest {
  @get:Rule val compose = createComposeRule()
  private val date = LocalDate.of(2026, 10, 2)
  private val zone = ZoneId.of("Europe/Zurich")

  @Test
  fun screenConnectsCalendarAndBottomBarWithoutUnavailableActions() {
    val vm =
        PlanningViewModel(
            PlanningRepository { flowOf(emptyList()) },
            Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone),
            zone,
        )
    var route: String? = null
    val navigation =
        object :
            NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext())) {
          override fun navigateToTab(selected: String) {
            route = selected
          }
        }
    compose.setContent { OncompanionTheme { PlanningScreen(navigation, vm) } }
    compose.onNodeWithTag(C.Tag.planning_empty).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
    compose.onNodeWithText("Tap + to add a doctor appointment.").assertDoesNotExist()
    compose.onNodeWithTag(Tab.PLANNING.testTag).assertIsSelected()
    compose.onNodeWithTag(C.Tag.planningDay(date.minusDays(1).toString())).performClick()
    compose.runOnIdle { assertEquals(date.minusDays(1), vm.uiState.value.selectedDate) }
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.runOnIdle {
      assertEquals(date.minusDays(1).plusWeeks(1), vm.uiState.value.selectedDate)
    }
    compose.onNodeWithTag(C.Tag.planning_previous).performClick()
    compose.onNodeWithTag(C.Tag.planning_today).performClick()
    compose.runOnIdle { assertEquals(date, vm.uiState.value.selectedDate) }
    compose.onNodeWithTag(Tab.OVERVIEW.testTag).performClick()
    compose.runOnIdle { assertEquals(Route.OVERVIEW, route) }
  }
}
