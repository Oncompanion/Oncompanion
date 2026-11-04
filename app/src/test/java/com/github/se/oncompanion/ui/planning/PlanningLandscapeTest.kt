// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.planning

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.se.oncompanion.model.planning.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.SMALL_LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for a calendar that used to leave only a thin agenda in landscape. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = LANDSCAPE_PHONE)
class PlanningLandscapeTest {
  @get:Rule val compose = createComposeRule()
  private val day = LocalDate.of(2026, 11, 4)
  private val zone = ZoneId.of("Europe/Zurich")
  private val entries =
      (0..19).map { index ->
        PlanningItem(
            PlanningSource.Appointment("a$index"),
            PlanningTiming.Timed(day.atTime(10, 0).atZone(zone).toInstant()),
            "Consultation $index",
            "Recorded location",
        )
      }

  private fun state() =
      PlanningUiState(day, day.minusDays(2), scheduledItems = entries, isLoading = false)

  /** Scrolling removes the header and exposes full rows while calendar actions remain reachable. */
  @Test fun landscapeReclaimsHeaderSpace() = checkScrollableCalendar()

  /** The same controls and final row fit a small phone held sideways. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscapeReclaimsHeaderSpace() = checkScrollableCalendar()

  private fun checkScrollableCalendar() {
    var previous = 0
    var next = 0
    var today = 0
    var selected: LocalDate? = null
    var added: LocalDate? = null
    compose.setContent {
      OncompanionTheme {
        PlanningContent(
            state(),
            day,
            zone,
            PlanningActions(
                { selected = it },
                { previous++ },
                { next++ },
                { today++ },
                {},
                { added = it },
            ),
            bottomBar = { BottomNavigationBar(Tab.PLANNING, {}) },
        )
      }
    }
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(entries.last().key)))
    compose.onNodeWithTag(C.Tag.planningItem(entries.last().key)).assertIsFullyVisible()
    // Reaching the last row alone would pass with the broken, permanently fixed header.
    compose.onNodeWithTag(C.Tag.planning_week).assertIsNotDisplayed()
    compose.onNodeWithTag(Tab.PLANNING.testTag).assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.planning_add).also { it.assertIsFullyVisible() }.performClick()
    assertEquals(day, added)
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(0)
    for (tag in listOf(C.Tag.planning_previous, C.Tag.planning_next, C.Tag.planning_today)) {
      compose.onNodeWithTag(tag).also { it.assertIsFullyVisible() }.performClick()
    }
    compose
        .onNodeWithTag(C.Tag.planningDay(day.toString()))
        .also { it.assertIsFullyVisible() }
        .performClick()
    assertEquals(day, selected)
    assertEquals(listOf(1, 1, 1), listOf(previous, next, today))
  }

  /** Loading, empty and error feedback do not make the calendar or retry inaccessible. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun feedbackStatesKeepTheirActionsReachable() {
    var current by mutableStateOf(state())
    var retries = 0
    compose.setContent {
      OncompanionTheme {
        PlanningContent(current, day, zone, PlanningActions({}, {}, {}, {}, { retries++ }))
      }
    }
    val cases =
        listOf(
            state().copy(scheduledItems = emptyList(), isLoading = true) to C.Tag.planning_loading,
            state().copy(scheduledItems = emptyList(), hasError = true) to C.Tag.planning_error,
            state().copy(scheduledItems = emptyList()) to C.Tag.planning_empty,
        )
    for ((value, tag) in cases) {
      compose.runOnIdle { current = value }
      if (tag == C.Tag.planning_empty) compose.onNodeWithTag(tag).performScrollTo()
      if (value.isLoading) compose.onNodeWithTag(tag).assertIsDisplayed()
      else compose.onNodeWithTag(tag).assertIsFullyVisible()
      if (value.hasError)
          compose
              .onNodeWithTag(C.Tag.planning_retry)
              .also { it.assertIsFullyVisible() }
              .performClick()
    }
    assertEquals(1, retries)
    compose.onNodeWithTag(C.Tag.planning_today).performScrollTo().assertIsFullyVisible()
  }

  /** Revoking access hides entries but keeps the calendar usable in landscape. */
  @Test fun compactCaregiverAgendaFollowsSharingChanges() = checkCaregiverSharingChanges()

  /** Calendar navigation also stays reachable on a smaller landscape phone. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallCompactCaregiverAgendaFollowsSharingChanges() = checkCaregiverSharingChanges()

  private fun checkCaregiverSharingChanges() {
    var current by mutableStateOf(state())
    var previous = 0
    var next = 0
    var today = 0
    var selected: LocalDate? = null
    compose.setContent {
      OncompanionTheme {
        PlanningContent(
            current,
            day,
            zone,
            PlanningActions({ selected = it }, { previous++ }, { next++ }, { today++ }, {}),
            caregiverOf = "Anne",
        )
      }
    }
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(entries.last().key)))
    compose.onNodeWithTag(C.Tag.planningItem(entries.last().key)).assertIsFullyVisible()
    compose.runOnIdle { current = current.copy(isNotShared = true) }
    compose.onNodeWithTag(C.Tag.not_shared).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.planning_list).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planningItem(entries.last().key)).assertDoesNotExist()
    // Check the controls before restoring access: restoring first would hide this regression.
    for (tag in listOf(C.Tag.planning_previous, C.Tag.planning_next, C.Tag.planning_today)) {
      compose.onNodeWithTag(tag).performScrollTo().also { it.assertIsFullyVisible() }.performClick()
    }
    compose
        .onNodeWithTag(C.Tag.planningDay(day.toString()))
        .performScrollTo()
        .also { it.assertIsFullyVisible() }
        .performClick()
    assertEquals(listOf(1, 1, 1), listOf(previous, next, today))
    assertEquals(day, selected)
    compose.onNodeWithTag(C.Tag.planning_list).assertDoesNotExist()
    compose.runOnIdle { current = current.copy(isNotShared = false) }
    compose.onNodeWithTag(C.Tag.not_shared).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToIndex(0)
    compose.onNodeWithTag(C.Tag.planning_today).assertIsFullyVisible()
  }

  /** Compact agenda position restores with the composition and resets for another selected day. */
  @Test
  fun compactScrollRestoresAndChangingDateReturnsToTheCalendar() {
    var current by mutableStateOf(state())
    val restoration = StateRestorationTester(compose)
    restoration.setContent {
      OncompanionTheme { PlanningContent(current, day, zone, PlanningActions({}, {}, {}, {}, {})) }
    }
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(entries.last().key)))
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(C.Tag.planningItem(entries.last().key)).assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.planning_week).assertIsNotDisplayed()
    compose.runOnIdle { current = current.copy(selectedDate = day.plusDays(1)) }
    compose.onNodeWithTag(C.Tag.planning_week).assertIsFullyVisible()
  }
}
