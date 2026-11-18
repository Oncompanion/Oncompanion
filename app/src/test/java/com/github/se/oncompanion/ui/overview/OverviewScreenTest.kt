// Co-authored-by: Codex <noreply@openai.com>
// Co-authored-by: Claude Code <noreply@anthropic.com>

package com.github.se.oncompanion.ui.overview

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.navigation.NavHostController
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.R
import com.github.se.oncompanion.domain.planning.ownPlanning
import com.github.se.oncompanion.model.carecircle.CarePermission
import com.github.se.oncompanion.model.planning.FakePlanningRepository
import com.github.se.oncompanion.model.planning.PlanningItem
import com.github.se.oncompanion.model.planning.PlanningSource
import com.github.se.oncompanion.model.planning.PlanningTiming
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.assertIsFullyVisible
import com.github.se.oncompanion.ui.assertSameColor
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.colorAt
import com.github.se.oncompanion.ui.common.LakeButtonDefaults
import com.github.se.oncompanion.ui.navigation.BottomNavigationBar
import com.github.se.oncompanion.ui.navigation.NavigationActions
import com.github.se.oncompanion.ui.navigation.Route
import com.github.se.oncompanion.ui.navigation.Screen
import com.github.se.oncompanion.ui.navigation.Tab
import com.github.se.oncompanion.ui.theme.DarkLakeColors
import com.github.se.oncompanion.ui.theme.LightLakeColors
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import com.github.se.oncompanion.ui.theme.PrimaryContainerDark
import com.github.se.oncompanion.ui.theme.PrimaryContainerLight
import com.github.se.oncompanion.ui.theme.SurfaceContainerLowestDark
import com.github.se.oncompanion.ui.theme.SurfaceContainerLowestLight
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class OverviewScreenTest {

  // An activity rule, so the phone can be rotated (the activity recreated)
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  private val context: Context = ApplicationProvider.getApplicationContext()

  /** Saturday 3 October 2026, 9:00. */
  private val morning = LocalDateTime.of(2026, 10, 3, 9, 0)

  private val appointment =
      NextAppointment(
          id = "check-up",
          title = "Oncology check-up",
          dateTime = LocalDateTime.of(2026, 10, 4, 9, 30),
          place = "HUG, Oncology day unit",
      )

  private val nextUp =
      NextUp(
          TodayItemKind.APPOINTMENT,
          appointment.id,
          appointment.title,
          appointment.dateTime,
          appointment.place,
      )

  private fun entry(id: String, hour: Int?, status: TodayItemStatus?, kind: TodayItemKind) =
      TodayEntry(
          key = id,
          kind = kind,
          time = hour?.let { LocalTime.of(it, 0) },
          title = "Title $id",
          subtitle = "Subtitle $id",
          status = status,
      )

  /** A clock stopped at [time], in UTC like the ViewModel's zone in these tests. */
  private fun clockAt(time: LocalDateTime) =
      Clock.fixed(time.toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

  private val loaded = OverviewUiState(now = morning, isLoading = false)

  /** Records the routes it is asked to open instead of navigating. */
  private class RecordingNavigationActions(navController: NavHostController) :
      NavigationActions(navController) {
    val openedRoutes = mutableListOf<String>()
    val openedTabs = mutableListOf<String>()

    override fun navigateTo(screen: String) {
      openedRoutes += screen
    }

    override fun navigateToTab(route: String) {
      openedTabs += route
    }

    val openedPlanningDays = mutableListOf<LocalDate>()

    override fun navigateToPlanningOn(date: LocalDate) {
      openedPlanningDays += date
    }
  }

  private fun setContent(
      state: OverviewUiState,
      onRetry: () -> Unit = {},
      caregiverOf: String? = null,
  ) {
    composeTestRule.setContent {
      OverviewContent(
          uiState = state,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = {},
          onRetry = onRetry,
          caregiverOf = caregiverOf,
      )
    }
  }

  /** The caregiver's day, which keeps the appointment card and the "Today" list for now. */
  private fun setCaregiverDay(state: OverviewUiState) = setContent(state, caregiverOf = "Anne")

  private fun setOverviewScreen(
      planning: FakePlanningRepository = FakePlanningRepository(),
      caregiverOf: String? = null,
      onSwitchPatient: () -> Unit = {},
  ): RecordingNavigationActions {
    val viewModel =
        OverviewViewModel(
            authRepository = FakeAuthRepository(),
            profileRepository = FakeUserProfileRepository(),
            observePlanning = ownPlanning(planning),
            clock = clockAt(morning),
            zoneId = ZoneOffset.UTC,
        )
    lateinit var navigationActions: RecordingNavigationActions
    composeTestRule.setContent {
      navigationActions = RecordingNavigationActions(TestNavHostController(LocalContext.current))
      OverviewScreen(
          navigationActions = navigationActions,
          viewModel = viewModel,
          caregiverOf = caregiverOf,
          onSwitchPatient = onSwitchPatient,
      )
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
      OverviewContent(
          uiState = state,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = {},
          onRetry = {},
      )
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
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun errorState_retryIsFullyVisibleInLandscape_aboveTheBottomBar() {
    composeTestRule.setContent {
      OncompanionTheme {
        OverviewContent(
            uiState = loaded.copy(hasError = true),
            onShortcutClick = {},
            onOpenPlanning = {},
            onOpenAppointment = {},
            onRetry = {},
            bottomBar = { BottomNavigationBar(Tab.OVERVIEW, {}) },
        )
      }
    }

    val retry = composeTestRule.onNodeWithTag(C.Tag.overview_retry).performScrollTo()
    // Not squeezed by a space too short for it either
    retry.assertHeightIsAtLeast(LakeButtonDefaults.MinHeight).assertIsFullyVisible()
    // Nothing clips content under the bottom bar, so check it's above the bar too
    val bar = composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).fetchSemanticsNode()
    val bottom = retry.fetchSemanticsNode().boundsInRoot.bottom
    assertTrue(
        "Retry ends at $bottom, the bar starts at ${bar.boundsInRoot.top}",
        bottom <= bar.boundsInRoot.top,
    )
  }

  @Test
  fun caregiverEmptyDay_showsNoAppointment_andNothingToday() {
    setCaregiverDay(loaded)
    composeTestRule.onNodeWithTag(C.Tag.overview_no_appointment).assertIsDisplayed()
    assertTextInside(
        context.getString(R.string.overview_no_appointment),
        C.Tag.overview_no_appointment,
    )
    composeTestRule.onNodeWithTag(C.Tag.overview_today_empty).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_today_list).assertDoesNotExist()
  }

  @Test
  fun caregiverNextAppointment_showsTitleDateAndPlace() {
    setCaregiverDay(loaded.copy(nextAppointment = appointment))

    val card = C.Tag.overview_next_appointment
    composeTestRule.onNodeWithTag(card).assertIsDisplayed()
    assertTextInside("Oncology check-up", card)
    assertTextInside("Tomorrow, Sun 4 Oct · 09:30", card)
    assertTextInside("HUG, Oncology day unit", card)
  }

  @Test
  fun caregiverAppointment_saysTodayOrOnlyItsDate() {
    var state by mutableStateOf(loaded)
    composeTestRule.setContent { OverviewContent(state, {}, {}, {}, {}, caregiverOf = "Anne") }
    listOf(
            morning.withHour(15) to "Today, Sat 3 Oct · 15:00",
            morning.plusDays(5) to "Thu 8 Oct · 09:00",
        )
        .forEach { (time, shown) ->
          state = loaded.copy(nextAppointment = appointment.copy(dateTime = time, place = null))
          composeTestRule.waitForIdle()
          assertTextInside(shown, C.Tag.overview_next_appointment)
        }
  }

  @Test
  fun caregiverTodayItems_showTimeTitleSubtitleAndStatus() {
    val entries =
        listOf(
            entry("med", 8, TodayItemStatus.DONE, TodayItemKind.MEDICATION),
            entry("yoga", 10, TodayItemStatus.NEXT, TodayItemKind.EVENT),
            entry("visit", 15, TodayItemStatus.LATER, TodayItemKind.APPOINTMENT),
        )
    setCaregiverDay(loaded.copy(todayEntries = entries))

    composeTestRule.onNodeWithTag(C.Tag.overview_today_list).assertIsDisplayed()
    entries.forEach { entry ->
      val row = C.Tag.overviewTodayItem(entry.key)
      composeTestRule.onNodeWithTag(row).performScrollTo().assertIsDisplayed()
      assertTextInside(entry.title, row)
      assertTextInside(entry.subtitle!!, row)
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
  fun screen_showsTodaysNextEvent_beforeALaterAppointment_andTodaysMedications() {
    val yoga =
        PlanningItem(
            PlanningSource.Event("gentle-yoga"),
            PlanningTiming.Timed(morning.withHour(10).toInstant(ZoneOffset.UTC)),
            "Gentle yoga for patients",
            subtitle = "Wellness center",
        )
    val checkUp =
        PlanningItem(
            PlanningSource.Appointment("check-up"),
            PlanningTiming.Timed(appointment.dateTime.toInstant(ZoneOffset.UTC)),
            appointment.title,
            subtitle = appointment.place,
        )
    val ondansetron =
        PlanningItem(
            PlanningSource.Medication("rx", "ondansetron"),
            PlanningTiming.DateOnly(morning.toLocalDate()),
            "Ondansetron",
            frequency = "Twice a day",
            dosage = "8 mg",
        )
    val navigationActions =
        setOverviewScreen(
            FakePlanningRepository().apply { items.value = listOf(yoga, checkUp, ondansetron) }
        )

    assertTextInside("Gentle yoga for patients", C.Tag.overview_next_up)
    assertTextInside("10:00 · Wellness center", C.Tag.overview_next_up)
    val medication = C.Tag.overviewMedication(ondansetron.key)
    assertTextInside("Ondansetron 8 mg", medication)
    assertTextInside("Twice a day", medication)
    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_today_list).assertDoesNotExist()

    composeTestRule.onNodeWithTag(C.Tag.overview_next_up).performClick()
    // Each card's link keeps the Figma's 20 dp row but a 48 dp touch target, and only opens
    // Planning: the Next up link doesn't also open the event
    listOf(C.Tag.overview_next_up_planning_link, C.Tag.overview_planning_link).forEach {
      composeTestRule.onNodeWithTag(it).assertTouchHeightIsEqualTo(48.dp).performClick()
    }
    composeTestRule.runOnIdle {
      // Both links open Planning on today, whatever day it showed last
      val today = morning.toLocalDate()
      assertEquals(listOf(today, today), navigationActions.openedPlanningDays)
      assertEquals(listOf(Screen.eventDetail("gentle-yoga")), navigationActions.openedRoutes)
    }
  }

  @Test
  fun nextUp_saysWhenAndWhere_andOpensTheAppointmentOrTheEvent() {
    var state by mutableStateOf(loaded)
    val opened = mutableListOf<String>()
    composeTestRule.setContent {
      OverviewContent(
          uiState = state,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = { opened += "appointment $it" },
          onRetry = {},
          onOpenEvent = { opened += "event $it" },
      )
    }
    val checkUp = NextUp(TodayItemKind.APPOINTMENT, "check-up", "Check-up", morning.withHour(15))
    listOf(
            checkUp.copy(place = "HUG") to "15:00 · HUG",
            checkUp.copy(dateTime = appointment.dateTime) to "Tomorrow, Sun 4 Oct · 09:30",
            checkUp.copy(dateTime = morning.plusDays(5), place = "HUG") to
                "Thu 8 Oct · 09:00 · HUG",
            checkUp.copy(kind = TodayItemKind.EVENT, id = "yoga", place = " ") to "15:00",
        )
        .forEach { (nextUp, shown) ->
          state = loaded.copy(nextUp = nextUp)
          composeTestRule.waitForIdle()
          composeTestRule.onNodeWithTag(C.Tag.overview_next_up).assertTextContains(shown)
          composeTestRule.onNodeWithTag(C.Tag.overview_next_up).performClick()
        }
    composeTestRule.runOnIdle {
      assertEquals(
          List(3) { "appointment check-up" } + "event yoga",
          opened,
      )
    }
  }

  private fun medication(id: String, frequency: String? = "Once a day") =
      TodayMedication(key = id, name = "Medication $id", frequency = frequency)

  @Test
  fun medications_showTheFirstTwo_andMoreOpensPlanning_onlyWhenThereAreMore() {
    var state by mutableStateOf(loaded)
    var openedPlanning = 0
    composeTestRule.setContent {
      OverviewContent(state, {}, onOpenPlanning = { openedPlanning++ }, {}, {})
    }
    listOf(2, 5).forEach { count ->
      state = loaded.copy(todayMedications = List(count) { medication("m$it") })
      composeTestRule.waitForIdle()
      listOf("m0", "m1").forEach {
        composeTestRule.onNodeWithTag(C.Tag.overviewMedication(it)).assertIsDisplayed()
      }
      composeTestRule.onNodeWithTag(C.Tag.overviewMedication("m2")).assertDoesNotExist()
      val title =
          if (count > 2) context.getString(R.string.overview_today_medications_count, count)
          else context.getString(R.string.overview_today_medications)
      assertTextInside(title, C.Tag.overview_medications)
      val more = composeTestRule.onNodeWithTag(C.Tag.overview_more_medications)
      if (count > 2) more.assertTextContains("3 more").assertHeightIsAtLeast(48.dp).performClick()
      else more.assertDoesNotExist()
    }
    composeTestRule.runOnIdle { assertEquals(1, openedPlanning) }
  }

  @Test
  fun medication_withoutFrequency_showsNoChip_andAnUnconfirmedOneSaysWhoAddedIt() {
    setContent(
        loaded.copy(
            todayMedications =
                listOf(medication("a", frequency = null).copy(notConfirmedAddedBy = "Sam"))
        )
    )
    val row = C.Tag.overviewMedication("a")
    assertTextInside(context.getString(R.string.added_by_not_confirmed, "Sam"), row)
    composeTestRule
        .onNode(hasText("Once a day") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
        .assertDoesNotExist()
  }

  @Test
  fun nothingPlanned_replacesBothCards_andOpensPlanning_butMedicationsAloneShowWithoutNextUp() {
    var state by mutableStateOf(loaded)
    var openedPlanning = 0
    composeTestRule.setContent {
      OverviewContent(state, {}, onOpenPlanning = { openedPlanning++ }, {}, {})
    }

    assertTextInside(
        context.getString(R.string.overview_nothing_today),
        C.Tag.overview_nothing_planned,
    )
    composeTestRule.onNodeWithTag(C.Tag.overview_next_up).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_medications).assertDoesNotExist()
    // It opens Planning, where the day is filled in
    composeTestRule.onNodeWithTag(C.Tag.overview_nothing_planned).performClick()
    composeTestRule.runOnIdle { assertEquals(1, openedPlanning) }

    state = loaded.copy(todayMedications = listOf(medication("a")))
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag(C.Tag.overview_medications).assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_nothing_planned).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_next_up).assertDoesNotExist()
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
          onOpenAppointment = {},
          onRetry = {},
          bottomBar = { Text("bar", Modifier.testTag("bar")) },
      )
    }
    composeTestRule.onNodeWithTag("bar").assertIsDisplayed()
  }

  // ----- Review fixes -----

  @Test
  fun caregiverHappeningNow_showsANowChip() {
    setCaregiverDay(
        loaded.copy(
            todayEntries = listOf(entry("chemo", 9, TodayItemStatus.NOW, TodayItemKind.APPOINTMENT))
        )
    )
    assertTextInside(context.getString(R.string.overview_now), C.Tag.overviewTodayItem("chemo"))
  }

  @Test
  fun caregiverMedicationWithoutTime_showsNoSetTime_andNoStatus() {
    setCaregiverDay(
        loaded.copy(todayEntries = listOf(entry("med", null, null, TodayItemKind.MEDICATION)))
    )
    val row = C.Tag.overviewTodayItem("med")
    assertTextInside(context.getString(R.string.overview_no_set_time), row)
    listOf(R.string.overview_next, R.string.overview_now).forEach {
      composeTestRule
          .onNode(
              hasText(context.getString(it)) and hasAnyAncestor(hasTestTag(row)),
              useUnmergedTree = true,
          )
          .assertDoesNotExist()
    }
  }

  @Test
  fun caregiverMedicationOfAnUnconfirmedPrescription_saysItIsNotConfirmed() {
    setCaregiverDay(
        loaded.copy(
            todayEntries =
                listOf(
                    entry("med", null, null, TodayItemKind.MEDICATION)
                        .copy(notConfirmedAddedBy = "Sam")
                )
        )
    )
    assertTextInside(
        context.getString(R.string.added_by_not_confirmed, "Sam"),
        C.Tag.overviewTodayItem("med"),
    )
  }

  @Test
  fun screen_refreshesTheTime_whenItComesBack() {
    var time = morning
    val clock =
        object : Clock() {
          override fun getZone(): ZoneId = ZoneOffset.UTC

          override fun withZone(zone: ZoneId?): Clock = this

          override fun instant(): Instant = time.toInstant(ZoneOffset.UTC)
        }
    val viewModel =
        OverviewViewModel(
            authRepository = FakeAuthRepository(),
            profileRepository = FakeUserProfileRepository(),
            observePlanning = ownPlanning(FakePlanningRepository()),
            clock = clock,
            zoneId = ZoneOffset.UTC,
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

  private fun tileTops(): List<Float> =
      listOf(
              OverviewShortcut.SYMPTOMS,
              OverviewShortcut.PRESCRIPTIONS,
              OverviewShortcut.CARE_CIRCLE,
          )
          .map { composeTestRule.onNodeWithTag(it.testTag).fetchSemanticsNode().positionInRoot.y }

  // Text needs real measurements, and a real phone width (Pixel 8a), to decide the tiles' layout
  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w412dp-h916dp")
  fun tiles_areInOneRow_withTheDefaultFontSize() {
    setContent(loaded)
    assertEquals(1, tileTops().distinct().size)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  @Config(qualifiers = "w412dp-h916dp")
  fun tiles_stack_withTheLargestFontSize() {
    composeTestRule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        OverviewContent(
            uiState = loaded,
            onShortcutClick = {},
            onOpenPlanning = {},
            onOpenAppointment = {},
            onRetry = {},
        )
      }
    }
    val tops = tileTops()
    assertTrue("tiles should be one per row: $tops", tops[0] < tops[1] && tops[1] < tops[2])
  }

  @Test
  fun overviewScreen_showsBottomBarWithOverviewSelected() {
    setOverviewScreen()
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertIsDisplayed()
    composeTestRule.onNodeWithTag(Tab.OVERVIEW.testTag).assertIsSelected()
  }

  @Test
  fun clickingATab_opensItWithTabNavigation() {
    val navigationActions = setOverviewScreen()

    composeTestRule.onNodeWithTag(Tab.PLANNING.testTag).performClick()
    composeTestRule.onNodeWithTag(Tab.EVENTS.testTag).performClick()

    composeTestRule.runOnIdle {
      assertEquals(listOf(Route.PLANNING, Route.EVENTS), navigationActions.openedTabs)
      assertEquals(emptyList<String>(), navigationActions.openedRoutes)
    }
  }

  @Test
  fun overviewContent_hasNoBottomBarByDefault() {
    composeTestRule.setContent {
      OverviewContent(
          uiState = loaded,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = {},
          onRetry = {},
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.bottom_navigation_bar).assertDoesNotExist()
  }

  // --- Caregiver mode (US-20) ---

  @Test
  fun caregiverMode_titleNamesThePatient_andOpensWhoseCare() {
    var switches = 0
    setOverviewScreen(caregiverOf = "Anne", onSwitchPatient = { switches++ })

    composeTestRule.onNodeWithTag(C.Tag.overview_greeting).assertDoesNotExist()
    composeTestRule
        .onNodeWithTag(C.Tag.overview_whose_care)
        .assertTextContains(context.getString(R.string.overview_care_of, "Anne"))
        .assertTextContains("Saturday 3 October")
        .performClick()
    composeTestRule.runOnIdle { assertEquals(1, switches) }
  }

  @Test
  fun caregiverMode_hidesTheShortcutsAndThePatientCards_butKeepsTheProfile() {
    composeTestRule.setContent {
      OverviewContent(
          uiState = loaded,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = {},
          onRetry = {},
          caregiverOf = "Anne",
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()
    composeTestRule.onNodeWithTag(C.Tag.overview_no_appointment).assertIsDisplayed()
    // The patient's cards of the Overview UX Refactor aren't the caregiver's yet
    listOf(C.Tag.overview_next_up, C.Tag.overview_medications, C.Tag.overview_nothing_planned)
        .forEach { composeTestRule.onNodeWithTag(it).assertDoesNotExist() }
    composeTestRule.onNodeWithTag(OverviewShortcut.PROFILE.testTag).assertIsDisplayed()
  }

  @Test
  fun caregiverMode_errorDoesntMentionTheShortcuts() {
    composeTestRule.setContent {
      OverviewContent(
          uiState = loaded.copy(hasError = true),
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = {},
          onRetry = {},
          caregiverOf = "Anne",
      )
    }
    composeTestRule.onNodeWithTag(C.Tag.overview_error).assertIsDisplayed()
    composeTestRule
        .onNodeWithText(context.getString(R.string.overview_error_hint_caregiver))
        .assertExists()
    composeTestRule
        .onNodeWithText(context.getString(R.string.overview_error_hint))
        .assertDoesNotExist()
  }

  // ----- Caregiver mode (US-20) -----

  private fun setCaregiverContent(
      state: OverviewUiState,
      onOpenAppointment: (String) -> Unit = {},
  ) {
    composeTestRule.setContent {
      OverviewContent(
          uiState = state,
          onShortcutClick = {},
          onOpenPlanning = {},
          onOpenAppointment = onOpenAppointment,
          onRetry = {},
          caregiverOf = "Anne",
      )
    }
  }

  @Test
  fun caregiver_planningNotShared_saysSo_insteadOfTheDay() {
    setCaregiverContent(loaded.copy(isNotShared = true))

    composeTestRule.onNodeWithText("Anne doesn't share Planning with you").assertIsDisplayed()
    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).assertDoesNotExist()
  }

  @Test
  fun caregiver_tappingTheNextAppointment_opensItsReadOnlyPage() {
    var opened: String? = null
    setCaregiverContent(
        loaded.copy(
            nextAppointment = NextAppointment("chemo", "Chemotherapy", morning.plusDays(1))
        ),
        onOpenAppointment = { opened = it },
    )

    composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment).performClick()

    assertEquals("chemo", opened)
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun caregiver_planningNotShared_isFullyVisibleInLandscape() {
    setCaregiverContent(loaded.copy(isNotShared = true))

    composeTestRule.onNodeWithTag(C.Tag.not_shared).assertIsFullyVisible()
  }

  @Test
  fun caregiver_getsTheSymptomsTile_onlyWhenShared() {
    setCaregiverContent(loaded.copy(sharedSections = setOf(CarePermission.SYMPTOMS)))

    composeTestRule.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertDoesNotExist()
    composeTestRule.onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag).assertDoesNotExist()
  }

  @Test
  fun caregiver_getsThePrescriptionsTile_onlyWhenShared() {
    setCaregiverContent(loaded.copy(sharedSections = setOf(CarePermission.PRESCRIPTIONS)))

    composeTestRule.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertDoesNotExist()
    composeTestRule.onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag).assertDoesNotExist()
  }

  @Test
  fun caregiver_withEverythingShared_neverGetsTheCareCircleTile() {
    setCaregiverContent(loaded.copy(sharedSections = CarePermission.entries.toSet()))

    composeTestRule.onNodeWithTag(OverviewShortcut.SYMPTOMS.testTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(OverviewShortcut.PRESCRIPTIONS.testTag).assertIsDisplayed()
    composeTestRule.onNodeWithTag(OverviewShortcut.CARE_CIRCLE.testTag).assertDoesNotExist()
  }

  @Test
  fun caregiver_withNothingShared_hasNoShortcuts() {
    setCaregiverContent(loaded)

    composeTestRule.onNodeWithTag(C.Tag.overview_shortcuts).assertDoesNotExist()
  }

  @Test
  fun nothingToConfirm_showsNoBadge() {
    setContent(loaded)
    composeTestRule.onNodeWithTag(C.Tag.overview_prescriptions_badge).assertDoesNotExist()
  }

  @Test
  fun prescriptionsToConfirm_badgeSaysHowMany_toScreenReadersToo() {
    setContent(loaded.copy(prescriptionsToConfirm = 2))

    composeTestRule
        .onNodeWithTag(C.Tag.overview_prescriptions_badge, useUnmergedTree = true)
        .assertIsDisplayed()
        .assertContentDescriptionEquals("2 prescriptions to confirm")
  }

  // ----- Look (Figma "US-23 / Overview – Dashboard") -----

  private fun bounds(tag: String) =
      composeTestRule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

  @Test
  fun header_isOnTop_andTheFirstCardOverlapsItsLowerEdge_whateverTheDay() {
    var state by mutableStateOf(loaded)
    composeTestRule.setContent { OverviewContent(state, {}, {}, {}, {}) }

    listOf(
            loaded.copy(nextUp = nextUp) to C.Tag.overview_next_up,
            loaded.copy(todayMedications = listOf(medication("a"))) to C.Tag.overview_medications,
            loaded to C.Tag.overview_nothing_planned,
        )
        .forEach { (shown, cardTag) ->
          state = shown
          composeTestRule.waitForIdle()
          val header = bounds(C.Tag.overview_header)
          val card = bounds(cardTag)
          assertEquals(0.dp, header.top)
          // The strip behind the status bar, so scrolled cards never pass under its icons (its
          // look is checked in PaperLakeTest: Robolectric's status bar has no height)
          composeTestRule
              .onNodeWithTag(C.Tag.overview_status_bar, useUnmergedTree = true)
              .assertExists()
          assertTrue("the full header, $cardTag", header.height >= 210.dp)
          assertTrue(
              "the card starts on the header, $cardTag",
              card.top > header.top && card.top < header.bottom,
          )
          assertTrue(
              "the greeting stays above the card",
              bounds(C.Tag.overview_greeting).bottom <= card.top,
          )
        }
  }

  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscape_headerIsTheBand_andTheCardIsBelowIt() {
    setContent(loaded.copy(nextUp = nextUp))

    val header = bounds(C.Tag.overview_header)
    assertTrue("the band, not the 210 dp header: ${header.height}", header.height < 210.dp)
    assertTrue(bounds(C.Tag.overview_next_up).top >= header.bottom)
  }

  @Test
  fun withoutADay_nothingOverlapsTheHeader() {
    var state by mutableStateOf(OverviewUiState(now = morning))
    composeTestRule.setContent { OverviewContent(state, {}, {}, {}, {}) }

    listOf(
            OverviewUiState(now = morning) to C.Tag.overview_loading,
            loaded.copy(hasError = true) to C.Tag.overview_error,
        )
        .forEach { (shown, tag) ->
          state = shown
          composeTestRule.waitForIdle()
          assertTrue(tag, bounds(tag).top >= bounds(C.Tag.overview_header).bottom)
        }
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun caregiverAt200Percent_aRowsTimeGoesAboveItsTitle_andEverythingCanBeScrolledToWhole() {
    val med = entry("med", 8, TodayItemStatus.DONE, TodayItemKind.MEDICATION)
    setCaregiverDay(
        loaded.copy(
            nextAppointment = appointment,
            todayEntries = listOf(med),
            sharedSections = setOf(CarePermission.SYMPTOMS),
        )
    )

    val row = C.Tag.overviewTodayItem("med")
    composeTestRule.onNodeWithTag(row).performScrollTo()
    fun textIn(text: String) =
        composeTestRule
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
    assertTrue("08:00 above its title", textIn("08:00").bottom <= textIn(med.title).top)
    listOf(
            C.Tag.overview_whose_care,
            C.Tag.overview_next_appointment,
            row,
            C.Tag.overview_see_planning,
            C.Tag.overview_shortcut_symptoms,
        )
        .forEach { composeTestRule.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }
  }

  @Test
  @Config(qualifiers = "w360dp-h640dp", fontScale = 2f)
  fun patientAt200Percent_everyCardAndLinkCanBeScrolledToWhole() {
    setContent(
        loaded.copy(
            nextUp = nextUp,
            todayMedications =
                List(3) { medication("m$it").copy(name = "Dexamethasone 4 mg, long name $it") },
        )
    )
    listOf(
            C.Tag.overview_next_up,
            C.Tag.overview_next_up_planning_link,
            C.Tag.overview_planning_link,
            C.Tag.overviewMedication("m1"),
            C.Tag.overview_more_medications,
            C.Tag.overview_shortcut_care_circle,
        )
        .forEach { composeTestRule.onNodeWithTag(it).performScrollTo().assertIsFullyVisible() }
  }

  @Test
  fun caregiverDefaultFont_aRowsTimeIsBesideItsTitle() {
    setCaregiverDay(
        loaded.copy(todayEntries = listOf(entry("med", 8, null, TodayItemKind.MEDICATION)))
    )

    val row = C.Tag.overviewTodayItem("med")
    fun textIn(text: String) =
        composeTestRule
            .onNode(hasText(text) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
    val time = textIn("08:00")
    val title = textIn("Title med")
    assertTrue("same line", time.top < title.bottom && title.top < time.bottom)
    assertTrue("the time first", time.right <= title.left)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun caregiverCardsAreWhite_withATonalDatePill_tilesTonal_andTheSkyFollowsTheTime_inBothThemes() {
    var dark by mutableStateOf(false)
    var now by mutableStateOf(morning)
    var empty by mutableStateOf(false)
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) {
        OverviewContent(
            loaded.copy(
                now = now,
                nextAppointment = appointment.takeIf { !empty },
                todayEntries =
                    if (empty) emptyList()
                    else listOf(entry("med", 8, null, TodayItemKind.MEDICATION)),
                sharedSections = setOf(CarePermission.SYMPTOMS),
            ),
            {},
            {},
            {},
            {},
            caregiverOf = "Anne",
        )
      }
    }

    listOf(
            Triple(false, SurfaceContainerLowestLight, PrimaryContainerLight) to LightLakeColors,
            Triple(true, SurfaceContainerLowestDark, PrimaryContainerDark) to DarkLakeColors,
        )
        .forEach { (colors, lake) ->
          val (isDark, card, tile) = colors
          dark = isDark
          listOf(morning to lake.dawn.top, morning.withHour(20) to lake.dusk.top).forEach {
              (time, sky) ->
            now = time
            composeTestRule.waitForIdle()
            val header = composeTestRule.onNodeWithTag(C.Tag.overview_header)
            assertSameColor("sky at ${time.hour}h, dark=$isDark", sky, header.colorAt(4.dp, 4.dp))
          }
          // In the cards' side padding, away from the text and the rounded corners
          val next = composeTestRule.onNodeWithTag(C.Tag.overview_next_appointment)
          assertSameColor("appointment card, dark=$isDark", card, next.colorAt(8.dp, 60.dp))
          // In the pill, in the gap between its clock and "Tomorrow, ..."
          val cardBounds = bounds(C.Tag.overview_next_appointment)
          val date =
              composeTestRule
                  .onNode(
                      hasText("Tomorrow, Sun 4 Oct · 09:30") and
                          hasAnyAncestor(hasTestTag(C.Tag.overview_next_appointment)),
                      useUnmergedTree = true,
                  )
                  .getUnclippedBoundsInRoot()
          assertSameColor(
              "date pill, dark=$isDark",
              tile,
              next.colorAt(
                  date.left - cardBounds.left - 2.dp,
                  (date.top + date.bottom) / 2 - cardBounds.top,
              ),
          )
          val list = composeTestRule.onNodeWithTag(C.Tag.overview_today_list)
          assertSameColor("today list, dark=$isDark", card, list.colorAt(8.dp, 30.dp))
          val symptoms =
              composeTestRule.onNodeWithTag(C.Tag.overview_shortcut_symptoms).performScrollTo()
          assertSameColor("tile, dark=$isDark", tile, symptoms.colorAt(8.dp, 36.dp))

          // The empty cards are the same white cards
          empty = true
          composeTestRule.waitForIdle()
          val noAppointment = composeTestRule.onNodeWithTag(C.Tag.overview_no_appointment)
          assertSameColor("empty card, dark=$isDark", card, noAppointment.colorAt(8.dp, 60.dp))
          val nothingToday =
              composeTestRule.onNodeWithTag(C.Tag.overview_today_empty).performScrollTo()
          assertSameColor("nothing today, dark=$isDark", card, nothingToday.colorAt(4.dp, 4.dp))
          empty = false
        }
  }

  @Test
  fun phoneRotated_theDayIsStillShown() {
    val checkUp =
        PlanningItem(
            PlanningSource.Appointment("check-up"),
            PlanningTiming.Timed(appointment.dateTime.toInstant(ZoneOffset.UTC)),
            appointment.title,
            subtitle = appointment.place,
        )
    val viewModel =
        OverviewViewModel(
            authRepository = FakeAuthRepository(),
            profileRepository = FakeUserProfileRepository(),
            observePlanning =
                ownPlanning(FakePlanningRepository().apply { items.value = listOf(checkUp) }),
            clock = clockAt(morning),
            zoneId = ZoneOffset.UTC,
        )
    val show = {
      composeTestRule.activity.setContent {
        OverviewScreen(
            navigationActions =
                RecordingNavigationActions(TestNavHostController(LocalContext.current)),
            viewModel = viewModel,
        )
      }
    }
    composeTestRule.runOnUiThread(show)
    composeTestRule.onNodeWithTag(C.Tag.overview_next_up).assertIsDisplayed()

    // Rotating recreates the activity; the Overview's ViewModel survives it
    composeTestRule.activityRule.scenario.recreate()
    composeTestRule.runOnUiThread(show)

    composeTestRule.onNodeWithTag(C.Tag.overview_header).assertIsDisplayed()
    assertTextInside(appointment.title, C.Tag.overview_next_up)
  }

  @Test
  @GraphicsMode(GraphicsMode.Mode.NATIVE)
  fun patientCardsAreWhite_inBothThemes() {
    var dark by mutableStateOf(false)
    var state by mutableStateOf(loaded)
    composeTestRule.setContent {
      OncompanionTheme(darkTheme = dark) { OverviewContent(state, {}, {}, {}, {}) }
    }
    listOf(false to SurfaceContainerLowestLight, true to SurfaceContainerLowestDark).forEach {
        (isDark, card) ->
      dark = isDark
      listOf(
              loaded.copy(nextUp = nextUp, todayMedications = listOf(medication("a"))) to
                  listOf(C.Tag.overview_next_up, C.Tag.overview_medications),
              loaded to listOf(C.Tag.overview_nothing_planned),
          )
          .forEach { (shown, tags) ->
            state = shown
            composeTestRule.waitForIdle()
            tags.forEach { tag ->
              // In the card's bottom-left corner padding, away from the badge and the text
              val node = composeTestRule.onNodeWithTag(tag).performScrollTo()
              val height = node.fetchSemanticsNode().size.height
              val y = with(composeTestRule.density) { height.toDp() } - 6.dp
              assertSameColor("$tag, dark=$isDark", card, node.colorAt(24.dp, y))
            }
          }
    }
  }
}
