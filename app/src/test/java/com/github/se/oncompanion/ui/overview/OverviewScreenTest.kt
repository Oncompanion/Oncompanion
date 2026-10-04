package com.github.se.oncompanion.ui.overview

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.model.overview.FakeOverviewRepository
import com.github.se.oncompanion.model.overview.NextAppointment
import com.github.se.oncompanion.model.overview.OverviewSampleData
import com.github.se.oncompanion.model.overview.TodayItem
import com.github.se.oncompanion.model.overview.TodayItemKind
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Saturday 3 October 2026, 9:00. */
  private val morning = LocalDateTime.of(2026, 10, 3, 9, 0)

  private val appointment =
      NextAppointment(
          title = "Oncology check-up",
          dateTime = LocalDateTime.of(2026, 10, 4, 9, 30),
          doctor = "Dr. Martin",
          place = "HUG, Oncology day unit",
      )

  private fun entry(id: String, hour: Int, status: TodayItemStatus, kind: TodayItemKind) =
      TodayEntry(
          TodayItem(
              id = id,
              kind = kind,
              time = morning.withHour(hour),
              title = "Title $id",
              subtitle = "Subtitle $id",
              isTaken = status == TodayItemStatus.DONE,
          ),
          status,
      )

  private val loaded = OverviewUiState(now = morning, isLoading = false)

  /** Records the routes it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedRoutes = mutableListOf<String>()

    override fun navigateTo(screen: String) {
      openedRoutes += screen
    }
  }

  private fun setContent(state: OverviewUiState, onRetry: () -> Unit = {}) {
    composeTestRule.setContent {
      OverviewContent(
          uiState = state,
          onShortcutClick = {},
          onOpenPlanning = {},
          onRetry = onRetry,
      )
    }
  }

  private fun setOverviewScreen(
      repository: FakeOverviewRepository = FakeOverviewRepository()
  ): RecordingNavigationActions {
    val viewModel =
        OverviewViewModel(
            authRepository = FakeAuthRepository(),
            profileRepository = { FakeUserProfileRepository() },
            overviewRepository = repository,
            clock = { morning },
        )
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      OverviewScreen(navigationActions = navigationActions, viewModel = viewModel)
    }
    return navigationActions
  }

  private fun assertTextInside(text: String, parentTag: String) {
    composeTestRule
        .onNode(hasText(text) and hasAnyAncestor(hasTestTag(parentTag)), useUnmergedTree = true)
        .assertExists()
  }

  // ----- Greeting -----

  @Test
  fun greeting_usesTheTimeOfDay_andTheFirstName() {
    var state by mutableStateOf(loaded.copy(firstName = "Alex"))
    composeTestRule.setContent {
      OverviewContent(uiState = state, onShortcutClick = {}, onOpenPlanning = {}, onRetry = {})
    }
    listOf(
            9 to R.string.overview_greeting_morning_name,
            15 to R.string.overview_greeting_afternoon_name,
            20 to R.string.overview_greeting_evening_name,
        )
        .forEach { (hour, greeting) ->
          composeTestRule.runOnIdle { state = state.copy(now = morning.withHour(hour)) }
          composeTestRule
              .onNodeWithTag(C.Tag.overview_greeting)
              .assertTextEquals(context.getString(greeting, "Alex"))
        }
  }

  @Test
  fun greetingWithoutName_andTodaysDate() {
    setContent(loaded.copy(now = morning.withHour(20)))
    composeTestRule
        .onNodeWithTag(C.Tag.overview_greeting)
        .assertTextEquals(context.getString(R.string.overview_greeting_evening))
    composeTestRule.onNodeWithTag(C.Tag.overview_date).assertTextEquals("Saturday 3 October")
  }

  // ----- Each state -----

  @Test
  fun loadingState_showsTheIndicator_andTheShortcuts() {
    setContent(OverviewUiState(now = morning))
    composeTestRule.onNodeWithTag(C.Tag.overview_loading).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).assertDoesNotExist()
    composeTestRule.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertExists()
  }

  @Test
  fun errorState_showsRetry_andKeepsTheShortcuts() {
    var retries = 0
    setContent(loaded.copy(hasError = true), onRetry = { retries++ })

    composeTestRule.onNodeWithTag(C.Tag.overview_error).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_retry).performClick()
    composeTestRule.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertExists()

    composeTestRule.runOnIdle { assertEquals(1, retries) }
  }

  @Test
  fun emptyDay_showsNoAppointment_andNothingToday() {
    setContent(loaded)
    composeTestRule.onNodeWithTag(C.Tag.overview_no_appointment).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.overview_no_appointment),
        C.Tag.overview_no_appointment,
    )
    composeTestRule.onNodeWithTag(C.Tag.overview_today_empty).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_today_list).assertDoesNotExist()
  }

  @Test
  fun nextAppointment_showsTitleDoctorDateAndPlace() {
    setContent(loaded.copy(nextAppointment = appointment))

    val card = C.Tag.overview_next_appointment
    composeTestRule.onNodeWithTag(card).assertIsDisplayed()
    assertTextInside("Oncology check-up", card)
    assertTextInside("With Dr. Martin", card)
    assertTextInside("Tomorrow, Sun 4 Oct · 09:30", card)
    assertTextInside("HUG, Oncology day unit", card)
  }

  @Test
  fun appointmentToday_saysToday() {
    setContent(
        loaded.copy(
            nextAppointment = appointment.copy(dateTime = morning.withHour(15), place = null)
        )
    )
    assertTextInside("Today, Sat 3 Oct · 15:00", C.Tag.overview_next_appointment)
  }

  @Test
  fun appointmentInSomeDays_showsOnlyItsDate() {
    setContent(
        loaded.copy(
            nextAppointment = appointment.copy(dateTime = morning.plusDays(5), doctor = null)
        )
    )
    assertTextInside("Thu 8 Oct · 09:00", C.Tag.overview_next_appointment)
  }

  @Test
  fun todayItems_showTimeTitleSubtitleAndStatus() {
    val entries =
        listOf(
            entry("med", 8, TodayItemStatus.DONE, TodayItemKind.MEDICATION),
            entry("yoga", 10, TodayItemStatus.NEXT, TodayItemKind.EVENT),
            entry("visit", 15, TodayItemStatus.LATER, TodayItemKind.APPOINTMENT),
        )
    setContent(loaded.copy(todayEntries = entries))

    composeTestRule.onNodeWithTag(C.Tag.overview_today_list).assertIsDisplayed()
    entries.forEach { entry ->
      val row = C.Tag.overviewTodayItem(entry.item.id)
      composeTestRule.onNodeWithTag(row).performScrollTo().assertIsDisplayed()
      assertTextInside(entry.item.title, row)
      assertTextInside(entry.item.subtitle!!, row)
    }
    assertTextInside("08:00", C.Tag.overviewTodayItem("med"))
    composeTestRule
        .onNode(
            hasContentDescription(context.getString(R.string.overview_done)) and
                hasAnyAncestor(hasTestTag(C.Tag.overviewTodayItem("med"))),
            useUnmergedTree = true,
        )
        .assertExists()
    assertTextInside(context.getString(R.string.overview_next), C.Tag.overviewTodayItem("yoga"))
  }

  // ----- Screen wired to the ViewModel and navigation -----

  @Test
  fun screen_showsTheRepositoryData() {
    setOverviewScreen(OverviewSampleData.repository(morning.toLocalDate()))

    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overviewTodayItem("gentle-yoga")).assertExists()
  }

  @Test
  fun appointmentCard_andSeePlanning_openPlanning() {
    val navigationActions = setOverviewScreen(FakeOverviewRepository(appointment))

    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).performClick()
    composeTestRule.onNodeWithTag(C.Tag.overview_see_planning).performScrollTo().performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf(Route.PLANNING, Route.PLANNING), navigationActions.openedRoutes)
    }
  }

  @Test
  fun shortcutTiles_andProfileButton_openTheirSection() {
    val navigationActions = setOverviewScreen()

    listOf(OverviewShortcut.SYMPTOMS, OverviewShortcut.PRESCRIPTIONS, OverviewShortcut.CARE_CIRCLE)
        .forEach { shortcut ->
          composeTestRule
              .onNode(hasTestTag(shortcut.testTag) and hasText(context.getString(shortcut.label)))
              .performScrollTo()
              .assertHasClickAction()
              .performClick()
        }
    composeTestRule
        .onNode(
            hasTestTag(OverviewShortcut.PROFILE.testTag) and
                hasContentDescription(context.getString(R.string.profile_title))
        )
        .performClick()

    composeTestRule.runOnIdle {
      assertEquals(
          listOf(Route.SYMPTOMS, Route.PRESCRIPTIONS, Route.CARE_CIRCLE, Route.PROFILE),
          navigationActions.openedRoutes,
      )
    }
  }

  @Test
  fun overviewContent_showsTheGivenBottomBar() {
    composeTestRule.setContent {
      OverviewContent(
          uiState = loaded,
          onShortcutClick = {},
          onOpenPlanning = {},
          onRetry = {},
          bottomBar = { Text("bar", Modifier.testTag("bar")) },
      )
    }
    composeTestRule.onNodeWithTag("bar").assertIsDisplayed()
  }

  // ----- Review fixes -----

  @Test
  fun notTakenMedication_showsANeutralChip() {
    setContent(
        loaded.copy(
            todayEntries =
                listOf(entry("med", 8, TodayItemStatus.NOT_TAKEN, TodayItemKind.MEDICATION))
        )
    )
    assertTextInside(context.getString(R.string.overview_not_taken), C.Tag.overviewTodayItem("med"))
  }

  @Test
  fun screen_refreshesTheTime_whenItComesBack() {
    var time = morning
    val viewModel =
        OverviewViewModel(
            authRepository = FakeAuthRepository(),
            profileRepository = { FakeUserProfileRepository() },
            overviewRepository = FakeOverviewRepository(),
            clock = { time },
        )
    // Hours later, the user comes back to the Overview
    time = morning.withHour(20)
    composeTestRule.setContent {
      OverviewScreen(
          navigationActions = NavigationActions(TestNavHostController(LocalContext.current)),
          viewModel = viewModel,
      )
    }

    composeTestRule
        .onNodeWithTag(C.Tag.overview_greeting)
        .assertTextEquals(context.getString(R.string.overview_greeting_evening))
  }
}
