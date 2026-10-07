package com.github.se.oncompanion.ui.planning

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
            FakePlanningRepository(),
            Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone),
            zone,
            SavedStateHandle(),
        )
    var selectedRoute: String? = null
    val navigation =
        object :
            NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext())) {
          override fun navigateToTab(route: String) {
            selectedRoute = route
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
    compose.runOnIdle { assertEquals(Route.OVERVIEW, selectedRoute) }
  }

  @Test
  fun screenRetriesRepositoryFailure() {
    val repository = FakePlanningRepository().apply { fail = true }
    val vm =
        PlanningViewModel(
            repository,
            Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), zone),
            zone,
            SavedStateHandle(),
        )
    val navigation =
        NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext()))
    compose.setContent { OncompanionTheme { PlanningScreen(navigation, vm) } }
    compose.onNodeWithTag(C.Tag.planning_error).assertIsDisplayed()
    compose.runOnIdle { repository.fail = false }
    compose.onNodeWithTag(C.Tag.planning_retry).performClick()
    compose.onNodeWithTag(C.Tag.planning_empty).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_error).assertDoesNotExist()
  }

  @Test
  fun mixedSourceUpdatesReachTheScreenThroughTheViewModel() {
    val medication =
        PlanningItem(
            PlanningSource.Medication("p", "m"),
            PlanningTiming.DateOnly(date),
            "Medication",
            frequency = "Twice a day",
        )
    val event =
        PlanningItem(
            PlanningSource.Event("e"),
            PlanningTiming.Timed(date.atTime(14, 0).atZone(zone).toInstant()),
            "Workshop",
            "Geneva",
        )
    val repository = FakePlanningRepository().apply { items.value = listOf(event, medication) }
    val vm =
        PlanningViewModel(
            repository,
            Clock.fixed(date.atTime(12, 0).atZone(zone).toInstant(), zone),
            zone,
            SavedStateHandle(),
        )
    val navigation =
        NavigationActions(TestNavHostController(ApplicationProvider.getApplicationContext()))
    compose.setContent { OncompanionTheme { PlanningScreen(navigation, vm) } }
    compose.onNodeWithText("Frequency: Twice a day").assertIsDisplayed()
    compose.onNodeWithText("Workshop").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_add).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planningItem(medication.key)).assertHasNoClickAction()
    compose.runOnIdle { repository.items.value = listOf(medication.copy(frequency = "As needed")) }
    compose.onNodeWithText("Frequency: As needed").assertIsDisplayed()
    compose.onNodeWithText("Workshop").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planning_scheduled).assertDoesNotExist()
  }
}
