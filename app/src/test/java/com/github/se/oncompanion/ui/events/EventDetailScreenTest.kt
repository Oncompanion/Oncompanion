// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.events

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.github.se.oncompanion.model.auth.AuthUser
import com.github.se.oncompanion.model.event.Event
import com.github.se.oncompanion.model.event.FakeEventRegistrationRepository
import com.github.se.oncompanion.model.event.FakeEventRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
          websiteUrl = "https://organizer.ch/yoga",
      )

  /** [yoga] loaded, with [registration] and nothing else open. */
  private fun loaded(
      registration: RegistrationStatus,
      event: Event = yoga,
      isConfirmingRemoval: Boolean = false,
  ) =
      EventDetailUiState(
          event = event,
          isLoading = false,
          registration = registration,
          isConfirmingRemoval = isConfirmingRemoval,
      )

  /** Records the links the screen opens, or fails like a phone without a browser. */
  private class RecordingUriHandler(private val canOpen: Boolean = true) : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
      if (!canOpen) throw IllegalArgumentException("Can't open $uri.")
      opened += uri
    }
  }

  private fun setContent(
      uiState: EventDetailUiState,
      onBack: () -> Unit = {},
      onRetry: () -> Unit = {},
      onConfirmRegistration: () -> Unit = {},
      onRequestRemoval: () -> Unit = {},
      onCancelRemoval: () -> Unit = {},
      onRemoveRegistration: () -> Unit = {},
      uriHandler: UriHandler = RecordingUriHandler(),
  ) {
    composeTestRule.setContent {
      CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        EventDetailContent(
            uiState = uiState,
            onBack = onBack,
            onRetry = onRetry,
            onConfirmRegistration = onConfirmRegistration,
            onRequestRemoval = onRequestRemoval,
            onCancelRemoval = onCancelRemoval,
            onRemoveRegistration = onRemoveRegistration,
        )
      }
    }
  }

  private fun assertTagged(text: String, tag: String) {
    composeTestRule.onNodeWithTag(tag).assertTextEquals(text)
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
  fun notFoundState_explainsAndGoesBackToEvents() {
    var backs = 0
    setContent(EventDetailUiState(isLoading = false, notFound = true), onBack = { backs++ })

    assertTextInside(
        context.getString(R.string.event_detail_not_found_supporting),
        C.Tag.event_detail_not_found,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_back_to_events).performClick()

    composeTestRule.runOnIdle { assertEquals(1, backs) }
  }

  @Test
  fun canConfirm_offersTheWebsiteAndConfirming_withTheNote() {
    var confirms = 0
    setContent(
        loaded(RegistrationStatus.CAN_CONFIRM),
        onConfirmRegistration = { confirms++ },
    )

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertIsDisplayed()
    assertTagged(
        context.getString(R.string.event_detail_registration_note),
        C.Tag.event_detail_registration_note,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_confirmed).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsEnabled().performClick()

    composeTestRule.runOnIdle { assertEquals(1, confirms) }
  }

  @Test
  fun visitingTheWebsite_opensTheOrganizersLink_withoutConfirming() {
    val uriHandler = RecordingUriHandler()
    var confirms = 0
    setContent(
        loaded(RegistrationStatus.CAN_CONFIRM),
        onConfirmRegistration = { confirms++ },
        uriHandler = uriHandler,
    )

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf("https://organizer.ch/yoga"), uriHandler.opened)
      assertEquals(0, confirms)
    }
  }

  @Test
  fun eventWithoutWebsite_offersOnlyConfirming() {
    setContent(loaded(RegistrationStatus.CAN_CONFIRM, event = yoga.copy(websiteUrl = null)))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsEnabled()
  }

  @Test
  fun eventWithAnInvalidWebsite_hidesTheWebsiteButton() {
    setContent(loaded(RegistrationStatus.CAN_CONFIRM, event = yoga.copy(websiteUrl = "organizer")))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertDoesNotExist()
  }

  @Test
  fun websiteThatCantOpen_saysSo_andThatConfirmingStillWorks() {
    setContent(
        loaded(RegistrationStatus.CAN_CONFIRM),
        uriHandler = RecordingUriHandler(canOpen = false),
    )

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).performClick()

    composeTestRule
        .onNode(hasText(context.getString(R.string.event_detail_website_error_can_confirm)))
        .assertIsDisplayed()
  }

  @Test
  fun websiteThatCantOpen_onceConfirmed_doesntOfferConfirming() {
    setContent(
        loaded(RegistrationStatus.CONFIRMED),
        uriHandler = RecordingUriHandler(canOpen = false),
    )

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).performClick()

    composeTestRule
        .onNode(hasText(context.getString(R.string.event_detail_website_error)))
        .assertIsDisplayed()
  }

  @Test
  fun confirmed_showsTheBanner_andOffersRemoval() {
    var removals = 0
    setContent(loaded(RegistrationStatus.CONFIRMED), onRequestRemoval = { removals++ })

    assertTextInside(
        context.getString(R.string.event_detail_registration_confirmed),
        C.Tag.event_detail_registration_confirmed,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove_dialog).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove).performClick()

    composeTestRule.runOnIdle { assertEquals(1, removals) }
  }

  @Test
  fun removalDialog_explainsIt_andRemoves() {
    var removals = 0
    var cancels = 0
    setContent(
        loaded(RegistrationStatus.CONFIRMED, isConfirmingRemoval = true),
        onRemoveRegistration = { removals++ },
        onCancelRemoval = { cancels++ },
    )

    assertTextInside(
        context.getString(R.string.event_detail_remove_message),
        C.Tag.event_detail_remove_dialog,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove_confirm).performClick()

    composeTestRule.runOnIdle {
      assertEquals(1, removals)
      assertEquals(0, cancels)
    }
  }

  @Test
  fun removalDialog_cancelOnlyCancels() {
    var removals = 0
    var cancels = 0
    setContent(
        loaded(RegistrationStatus.CONFIRMED, isConfirmingRemoval = true),
        onRemoveRegistration = { removals++ },
        onCancelRemoval = { cancels++ },
    )

    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove_cancel).performClick()

    composeTestRule.runOnIdle {
      assertEquals(0, removals)
      assertEquals(1, cancels)
    }
  }

  @Test
  fun startedEvent_disablesConfirming_andSaysWhy() {
    setContent(loaded(RegistrationStatus.CLOSED))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsNotEnabled()
    assertTagged(
        context.getString(R.string.event_detail_registration_closed),
        C.Tag.event_detail_registration_closed,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_note).assertDoesNotExist()
  }

  @Test
  fun startedEventWithoutWebsite_disablesConfirming() {
    setContent(loaded(RegistrationStatus.CLOSED, event = yoga.copy(websiteUrl = null)))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsNotEnabled()
  }

  @Test
  fun registrationError_keepsTheEvent_andRetries() {
    var retries = 0
    setContent(loaded(RegistrationStatus.ERROR), onRetry = { retries++ })

    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_event_title)
        .assertTextEquals("Gentle yoga for patients")
    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertIsDisplayed()
    assertTagged(
        context.getString(R.string.event_detail_registration_error),
        C.Tag.event_detail_registration_error,
    )
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_retry).performClick()

    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun registrationLoading_showsOnlyTheWebsite() {
    setContent(loaded(RegistrationStatus.LOADING))

    composeTestRule.onNodeWithTag(C.Tag.event_detail_visit_website).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_error).assertDoesNotExist()
  }

  @Test
  fun screen_confirmsAndRemovesTheRegistration() {
    val registrations = FakeEventRegistrationRepository()
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val zone = ZoneId.of("Europe/Zurich")
    // The day before the event
    val clock = Clock.fixed(yoga.startDateTime.minusDays(1).atZone(zone).toInstant(), zone)
    val viewModel =
        EventDetailViewModel("yoga", FakeEventRepository(listOf(yoga)), registrations, auth, clock)
    composeTestRule.setContent {
      EventDetailScreen(eventId = "yoga", onBack = {}, viewModel = viewModel)
    }

    composeTestRule
        .onNodeWithTag(C.Tag.event_detail_event_title)
        .assertTextEquals("Gentle yoga for patients")
    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).performClick()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_confirmed).assertIsDisplayed()
    composeTestRule.runOnIdle {
      assertEquals(setOf("yoga"), registrations.registeredEventIds("alice"))
    }

    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove).performClick()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove_confirm).performClick()

    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsEnabled()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_remove_dialog).assertDoesNotExist()
    composeTestRule.runOnIdle { assertTrue(registrations.registeredEventIds("alice").isEmpty()) }
  }

  @Test
  fun screen_comingBackAfterTheStart_closesTheRegistration() {
    val auth =
        FakeAuthRepository(onSignIn = { AuthUser(uid = "alice") }).also {
          runBlocking { it.signInWithGoogle("token") }
        }
    val zone = ZoneId.of("Europe/Zurich")
    var now = yoga.startDateTime.minusMinutes(5)
    val clock =
        object : Clock() {
          override fun getZone(): ZoneId = zone

          override fun withZone(zone: ZoneId?): Clock = this

          override fun instant() = now.atZone(zone).toInstant()
        }
    val viewModel =
        EventDetailViewModel(
            "yoga",
            FakeEventRepository(listOf(yoga)),
            FakeEventRegistrationRepository(),
            auth,
            clock,
        )
    assertEquals(RegistrationStatus.CAN_CONFIRM, viewModel.uiState.value.registration)

    // The event starts before the screen is shown (resumed) again
    now = yoga.startDateTime.plusMinutes(5)
    composeTestRule.setContent {
      EventDetailScreen(eventId = "yoga", onBack = {}, viewModel = viewModel)
    }

    composeTestRule.onNodeWithTag(C.Tag.event_detail_confirm).assertIsNotEnabled()
    composeTestRule.onNodeWithTag(C.Tag.event_detail_registration_closed).assertIsDisplayed()
  }
}
