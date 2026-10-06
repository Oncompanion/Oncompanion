package com.github.se.oncompanion.ui.planning

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlanningScreenTest {
  @get:Rule val compose = createComposeRule()
  private val date = LocalDate.of(2026, 10, 2)
  private val zone = ZoneId.of("Europe/Zurich")
  private val item =
      PlanningItem(
          PlanningSource.Appointment("test"),
          date.atTime(9, 0).atZone(zone).toInstant(),
          "Doctor consultation",
          "Room 3",
      )

  private fun render(
      state: PlanningUiState,
      onDate: (LocalDate) -> Unit = {},
      onAdd: (LocalDate) -> Unit = {},
      onItem: (PlanningItem) -> Unit = {},
      onRetry: () -> Unit = {},
      previous: () -> Unit = {},
      next: () -> Unit = {},
      today: () -> Unit = {},
      fontScale: Float = 1f,
  ) {
    compose.setContent {
      CompositionLocalProvider(
          LocalDensity provides Density(LocalDensity.current.density, fontScale)
      ) {
        OncompanionTheme {
          PlanningContent(
              state,
              date,
              zone,
              PlanningActions(onDate, previous, next, today, onRetry, onAdd, onItem),
          )
        }
      }
    }
  }

  private fun state(
      items: List<PlanningItem> = emptyList(),
      loading: Boolean = false,
      error: Boolean = false,
  ) = PlanningUiState(date, LocalDate.of(2026, 9, 28), items, loading, error)

  @Test
  fun emptyStateAndDates() {
    render(state())
    compose.onNodeWithText("Planning").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextEquals("Today — 02/10/2026")
    compose.onNodeWithTag(C.Tag.planning_empty).assertIsDisplayed()
    compose
        .onNodeWithTag(C.Tag.planningDay(date.toString()))
        .assertIsSelected()
        .assertContentDescriptionEquals("02/10/2026, Today")
    compose
        .onNodeWithTag(C.Tag.planningDay(date.minusDays(1).toString()))
        .assertContentDescriptionEquals("01/10/2026")
    compose.onNodeWithText("02", useUnmergedTree = true).assertExists()
  }

  @Test
  @Config(qualifiers = "w360dp-h800dp")
  fun narrowScreenShowsEveryDayAndSundayCanBeSelected() {
    var selected: LocalDate? = null
    render(state(), onDate = { selected = it }, fontScale = 1.5f)
    val weekBounds = compose.onNodeWithTag(C.Tag.planning_week).fetchSemanticsNode().boundsInRoot
    repeat(7) { offset ->
      val day = state().weekStart.plusDays(offset.toLong())
      val node = compose.onNodeWithTag(C.Tag.planningDay(day.toString()))
      node.assertIsDisplayed()
      val bounds = node.fetchSemanticsNode().boundsInRoot
      assertTrue(
          "$day must fit inside the week strip",
          bounds.left >= weekBounds.left && bounds.right <= weekBounds.right,
      )
    }
    val sunday = state().weekStart.plusDays(6)
    compose.onNodeWithTag(C.Tag.planningDay(sunday.toString())).performClick()
    assertEquals(sunday, selected)
  }

  @Test
  fun populatedRowsAndCallbacks() {
    var selected: LocalDate? = null
    var added: LocalDate? = null
    var opened: PlanningItem? = null
    render(
        state(listOf(item)),
        onDate = { selected = it },
        onAdd = { added = it },
        onItem = { opened = it },
    )
    compose.onNodeWithText("Doctor consultation").assertIsDisplayed()
    compose.onNodeWithText("Room 3").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planningItem(item.key)).performClick()
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    compose.onNodeWithTag(C.Tag.planningDay(date.minusDays(1).toString())).performClick()
    assertEquals(item, opened)
    assertEquals(date, added)
    assertEquals(date.minusDays(1), selected)
  }

  @Test
  fun loadingDoesNotShowEmpty() {
    render(state(loading = true))
    compose.onNodeWithTag(C.Tag.planning_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_empty).assertDoesNotExist()
  }

  @Test
  fun errorAndRetry() {
    var retries = 0
    render(state(error = true), onRetry = { retries++ })
    compose.onNodeWithTag(C.Tag.planning_error).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_retry).performClick()
    compose.onNodeWithTag(C.Tag.planning_empty).assertDoesNotExist()
    assertEquals(1, retries)
  }

  @Test
  fun cachedItemsRemainVisibleDuringRefreshAndError() {
    render(state(listOf(item.copy(subtitle = null)), loading = true, error = true))
    compose.onNodeWithText("Doctor consultation").assertIsDisplayed()
    compose.onNodeWithText("Room 3").assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.planning_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_error).assertIsDisplayed()
  }

  @Test
  fun dateNavigationCallbacks() {
    var previous = 0
    var next = 0
    var today = 0
    render(state(), previous = { previous++ }, next = { next++ }, today = { today++ })
    compose.onNodeWithTag(C.Tag.planning_previous).performClick()
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithTag(C.Tag.planning_today).performClick()
    assertEquals(1, previous)
    assertEquals(1, next)
    assertEquals(1, today)
  }

  @Test
  fun longListCanReachLastItemWithoutMovingHeader() {
    val items = (0 until 30).map { item.copy(source = PlanningSource.Appointment("item-$it")) }
    render(state(items))
    compose
        .onNodeWithTag(C.Tag.planning_list)
        .performScrollToNode(hasTestTag(C.Tag.planningItem(items.last().key)))
    compose.onNodeWithTag(C.Tag.planningItem(items.last().key)).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_heading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_add).assertIsDisplayed()
  }

  @Test
  fun upperBoundaryDisablesNextWeekAndUnsupportedDays() {
    val last = java.time.LocalDate.of(9999, 12, 31)
    render(PlanningUiState(last, last.minusDays(4), isLoading = false))
    compose.onNodeWithTag(C.Tag.planning_next).assertIsNotEnabled()
    compose
        .onNodeWithTag(C.Tag.planningDay("+10000-01-01"))
        .assertIsNotEnabled()
        .assertContentDescriptionEquals("Date outside supported range")
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextEquals("31/12/9999")
  }

  @Test
  fun lowerBoundaryDisablesPreviousWeek() {
    val first = java.time.LocalDate.of(1, 1, 1)
    render(PlanningUiState(first, first, isLoading = false))
    compose.onNodeWithTag(C.Tag.planning_previous).assertIsNotEnabled()
  }

  private fun checkTimeFormat(setting: String, expected: String) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val original = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
    try {
      Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, setting)
      render(state(listOf(item)))
      compose.onNodeWithText(expected).assertIsDisplayed()
    } finally {
      Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, original)
    }
  }

  @Test fun displays24HourTime() = checkTimeFormat("24", "09:00")

  @Test fun displays12HourTime() = checkTimeFormat("12", "9:00 AM")
}
