package com.github.se.oncompanion.ui.events

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.navigation.Tab
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventsScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val yoga =
      Event(
          id = "yoga",
          title = "Gentle yoga for patients",
          place = "Wellness center, Building B",
          startDateTime = LocalDateTime.of(2026, 10, 3, 10, 0),
          summary = "A relaxing session adapted to people in treatment.",
          description = "The full description, only shown in the event's detail.",
      )

  /** Records the tabs it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedTabs = mutableListOf<String>()
    val openedScreens = mutableListOf<String>()

    override fun navigateToTab(route: String) {
      openedTabs += route
    }

    override fun navigateTo(screen: String) {
      openedScreens += screen
    }
  }

  private fun setEventsScreen(
      viewModel: EventsViewModel = EventsViewModel(FakeEventRepository())
  ): RecordingNavigationActions {
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      EventsScreen(navigationActions = navigationActions, viewModel = viewModel)
    }
    return navigationActions
  }

  private fun setContent(uiState: EventsUiState, onRetry: () -> Unit = {}) {
    composeTestRule.setContent { EventsContent(uiState = uiState, onRetry = onRetry) }
  }

  // Clickable cards merge their texts, so look for the text in the unmerged tree
  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertIsDisplayed()
  }

  // ----- Screen wired to the ViewModel -----

  @Test
  fun eventsScreen_showsItsTitle() {
    setEventsScreen()
    composeTestRule.onNodeWithTag(C.Tag.events_screen).assertIsDisplayed()
    composeTestRule
        .onNodeWithTag(C.Tag.events_title)
        .assertIsDisplayed()
        .assertTextEquals(context.getString(R.string.events_screen_title))
  }

  @Test
  fun eventsScreen_byDefault_listsTheSampleEvents() {
    setEventsScreen()
    composeTestRule.onNodeWithTag(C.Tag.events_list).assertIsDisplayed()
    FakeEventRepository.sampleEvents(LocalDate.now()).forEach { event ->
      composeTestRule
          .onNodeWithTag(C.Tag.events_list)
          .performScrollToNode(hasTestTag(C.Tag.eventCard(event.id)))
      assertTextInside(event.title, C.Tag.eventCard(event.id))
    }
  }

  @Test
  fun eventsScreen_withEmptyRepository_showsTheEmptyState() {
    setEventsScreen(EventsViewModel(FakeEventRepository(emptyList())))
    composeTestRule.onNodeWithTag(C.Tag.events_empty_state).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.events_list).assertDoesNotExist()
  }

  @Test
  fun eventsScreen_showsBottomBarWithEventsSelected() {
    setEventsScreen()
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.EVENTS.testTag).assertIsSelected()
  }

  @Test
  fun clickingATab_opensItWithTabNavigation() {
    val navigationActions = setEventsScreen()

    composeTestRule.onNodeWithTag(Tab.OVERVIEW.testTag).performClick()
    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf(Route.OVERVIEW, Route.PLANNING), navigationActions.openedTabs)
    }
  }

  // ----- Each state of the content -----

  @Test
  fun loadingState_showsOnlyTheProgressIndicator() {
    setContent(EventsUiState(isLoading = true))
    composeTestRule.onNodeWithTag(C.Tag.events_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.events_list).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.events_empty_state).assertDoesNotExist()
  }

  @Test
  fun emptyState_showsTitleAndSupportingText() {
    setContent(EventsUiState(isLoading = false))
    composeTestRule.onNodeWithTag(C.Tag.events_empty_state).assertIsDisplayed()
    assertTextInside(context.getString(R.string.events_empty_title), C.Tag.events_empty_state)
    assertTextInside(
        context.getString(R.string.events_empty_supporting),
        C.Tag.events_empty_state,
    )
  }

  @Test
  fun errorState_showsMessage_andRetryCallsBack() {
    var retries = 0
    setContent(EventsUiState(isLoading = false, hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.events_error).assertIsDisplayed()
    assertTextInside(context.getString(R.string.events_error_title), C.Tag.events_error)
    composeTestRule.onNodeWithTag(C.Tag.events_retry).performClick()

    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun filledState_showsDatePlaceTitleAndSummaryOfEachEvent() {
    setContent(EventsUiState(events = listOf(yoga), isLoading = false))

    val card = C.Tag.eventCard(yoga.id)
    composeTestRule.onNodeWithTag(card).assertIsDisplayed()
    assertTextInside("Sat 3 Oct · 10:00", card)
    assertTextInside(yoga.place, card)
    assertTextInside(yoga.title, card)
    assertTextInside(yoga.summary, card)
    // The full description is only for the event's detail
    composeTestRule.onNodeWithText(yoga.description).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.events_empty_state).assertDoesNotExist()
  }

  @Test
  fun eventsContent_hasNoBottomBarByDefault() {
    setContent(EventsUiState(isLoading = false))
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertDoesNotExist()
  }

  @Test
  fun clickingAnEventCard_reportsTheEvent() {
    val clicked = mutableListOf<Event>()
    composeTestRule.setContent {
      EventsContent(
          uiState = EventsUiState(events = listOf(yoga), isLoading = false),
          onRetry = {},
          onEventClick = { clicked += it },
      )
    }

    composeTestRule.onNodeWithTag(C.Tag.eventCard(yoga.id)).performClick()

    composeTestRule.runOnIdle { assertEquals(listOf(yoga), clicked) }
  }

  @Test
  fun clickingAnEventCard_opensItsDetail() {
    val navigationActions = setEventsScreen()
    val first = FakeEventRepository.sampleEvents(LocalDate.now()).first()

    composeTestRule.onNodeWithTag(C.Tag.eventCard(first.id)).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf(Screen.eventDetail(first.id)), navigationActions.openedScreens)
    }
  }
}
