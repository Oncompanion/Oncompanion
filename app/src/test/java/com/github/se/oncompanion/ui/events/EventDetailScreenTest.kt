package com.github.se.oncompanion.ui.events

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.resources.C
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventDetailScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private val yoga =
      Event(
          id = "yoga",
          title = "Gentle yoga for patients",
          place = "Wellness center, Building B",
          startDateTime = LocalDateTime.of(2026, 10, 3, 10, 0),
          endDateTime = LocalDateTime.of(2026, 10, 3, 11, 30),
          category = "Wellbeing",
          summary = "Short summary for the list.",
          description = "A relaxing session adapted to people in treatment.",
      )

  private fun setContent(
      uiState: EventDetailUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
  ) {
    composeTestRule.setContent {
      EventDetailContent(uiState = uiState, onBack = onBack, onRetry = onRetry)
    }
  }

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  @Test
  fun loadedEvent_showsCategoryTitleDateTimePlaceAndDescription() {
    setContent(EventDetailUiState(event = yoga, isLoading = false))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_category).assertTextEquals("Wellbeing")
    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_event_title)
        .assertTextEquals("Gentle yoga for patients")
    assertTextInside("Sat 3 Oct · 10:00 – 11:30", C.Tag.event_detail_date_time)
    assertTextInside(
        context.getString(R.string.event_detail_date_time),
        C.Tag.event_detail_date_time,
    )
    assertTextInside("Wellness center, Building B", C.Tag.event_detail_location)
    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_description)
        .assertTextEquals("A relaxing session adapted to people in treatment.")
  }

  @Test
  fun eventWithoutEndOrCategory_showsOnlyTheStart() {
    setContent(
        EventDetailUiState(
            event = yoga.copy(endDateTime = null, category = null),
            isLoading = false,
        )
    )

    assertTextInside("Sat 3 Oct · 10:00", C.Tag.event_detail_date_time)
    composeTestRule.onNodeWithTag(C.Tag.event_detail_category).assertDoesNotExist()
  }

  @Test
  fun eventEndingAnotherDay_showsBothDates() {
    setContent(
        EventDetailUiState(
            event = yoga.copy(endDateTime = LocalDateTime.of(2026, 10, 4, 12, 0)),
            isLoading = false,
        )
    )

    assertTextInside("Sat 3 Oct · 10:00 – Sun 4 Oct · 12:00", C.Tag.event_detail_date_time)
  }

  @Test
  fun loadingState_showsTheIndicator() {
    setContent(EventDetailUiState())
    composeTestRule.onNodeWithTag(C.Tag.event_detail_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_event_title).assertDoesNotExist()
  }

  @Test
  fun notFoundState_showsAMessage() {
    setContent(EventDetailUiState(isLoading = false, notFound = true))
    composeTestRule.onNodeWithTag(C.Tag.event_detail_not_found).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.event_detail_not_found),
        C.Tag.event_detail_not_found,
    )
  }

  @Test
  fun errorState_retryCallsBack() {
    var retries = 0
    setContent(EventDetailUiState(isLoading = false, hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.event_detail_error).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_retry).performClick()

    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun backArrow_callsBack() {
    var backs = 0
    setContent(EventDetailUiState(event = yoga, isLoading = false), onBack = { backs++ })

    composeTestRule.onNodeWithTag(C.Tag.event_detail_back).performClick()

    composeTestRule.runOnIdle { assertEquals(1, backs) }
  }

  @Test
  fun screen_loadsTheEventFromTheRepository() {
    val viewModel = EventDetailViewModel("yoga", FakeEventRepository(listOf(yoga)))
    composeTestRule.setContent {
      EventDetailScreen(eventId = "yoga", onBack = {}, viewModel = viewModel)
    }

    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_event_title)
        .assertTextEquals("Gentle yoga for patients")
  }
}
