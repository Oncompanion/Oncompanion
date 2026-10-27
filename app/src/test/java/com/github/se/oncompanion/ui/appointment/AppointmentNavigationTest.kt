// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.domain.planning.PlanningRepositoryProvider
import com.github.se.oncompanion.model.appointment.Appointment
import com.github.se.oncompanion.model.appointment.AppointmentRepositoryProvider
import com.github.se.oncompanion.model.appointment.AppointmentType
import com.github.se.oncompanion.model.appointment.FakeAppointmentRepository
import com.github.se.oncompanion.model.auth.AuthRepositoryProvider
import com.github.se.oncompanion.model.user.FakeUserProfileRepository
import com.github.se.oncompanion.model.user.UserProfileRepositoryProvider
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.navigation.*
import com.github.se.oncompanion.ui.planning.PlanningDates
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the create flow with actual navigation, form and live Planning adapter. */
@RunWith(AndroidJUnit4::class)
class AppointmentNavigationTest {
  @get:Rule val compose = createComposeRule()
  private val repository = FakeAppointmentRepository()
  private val auth = FakeAuthRepository()
  private lateinit var nav: TestNavHostController

  private val originalAuth = AuthRepositoryProvider.repository
  private val originalAppointments = AppointmentRepositoryProvider.repository
  private val originalProfile = UserProfileRepositoryProvider.repository
  private val originalPlanning = PlanningRepositoryProvider.repository

  /** Provider overrides never leak into another test. */
  @After
  fun restoreProviders() {
    AuthRepositoryProvider.repository = originalAuth
    AppointmentRepositoryProvider.repository = originalAppointments
    PlanningRepositoryProvider.repository = originalPlanning
    UserProfileRepositoryProvider.repository = originalProfile
  }

  private fun show(startRoute: String = Route.PLANNING) {
    UserProfileRepositoryProvider.repository = FakeUserProfileRepository()
    AuthRepositoryProvider.repository = auth
    AppointmentRepositoryProvider.repository = repository
    runTest { auth.signInWithGoogle("patient") }
    compose.setContent {
      val context = LocalContext.current
      nav = remember {
        TestNavHostController(context).apply { navigatorProvider.addNavigator(ComposeNavigator()) }
      }
      AppNavHost(
          navController = nav,
          startRoute = startRoute,
      )
    }
  }

  private fun fill(title: String = "Visit") {
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput(title)
    compose.onNodeWithTag(C.Tag.appointment_time).performClick()
    compose.onNodeWithTag(C.Tag.appointment_confirm).performClick()
  }

  /** Cancel retains the selected week and creates no record. */
  @Test
  fun selectedDayPrefillsFormAndCancelReturnsWithoutWriting() {
    show()
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    val day = LocalDate.now().plusWeeks(1)
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    compose
        .onNodeWithTag(C.Tag.appointment_date)
        .assertTextContains(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    compose.onNodeWithTag(C.Tag.bottom_navigation_bar).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Discarded")
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextContains(PlanningDates.format(day))
    assertTrue(repository.appointments("uid-patient").isEmpty())
  }

  /** Local save updates the live agenda once and a reopened form starts empty. */
  @Test
  fun manualLocationSaveReturnsToExistingPlanningAndReopensFresh() {
    show()
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    compose.onNodeWithTag(C.Tag.hospital_search).assertDoesNotExist()
    fill()
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().performTextInput("Room 12")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.onNodeWithText("Visit").assertIsDisplayed()
    compose.onNodeWithText("Room 12").assertIsDisplayed()
    assertEquals(1, repository.appointments("uid-patient").size)
    compose.runOnIdle { assertEquals(Screen.PLANNING, nav.currentDestination?.route) }
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    assertEquals(
        "",
        compose
            .onNodeWithTag(C.Tag.appointment_title)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
            .text,
    )
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
  }

  /** A changed date returns to its week rather than hiding the newly saved appointment. */
  @Test
  fun savedDateResultChangesSelectionAndIsConsumed() {
    show()
    val savedDay = LocalDate.now().plusWeeks(2)
    // Exercise the same dated route used by Add, starting from an existing Planning entry.
    compose.runOnIdle { NavigationActions(nav).navigateTo(Screen.appointmentAdd(savedDay)) }
    fill("Later visit")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextContains(PlanningDates.format(savedDay))
    compose.onNodeWithText("Later visit").assertIsDisplayed()
    compose.runOnIdle {
      assertNull(
          nav.currentBackStackEntry?.savedStateHandle?.get<String>(Screen.APPOINTMENT_SAVED_DATE)
      )
    }
  }

  /** Add stays usable when the agenda's read fails; writing is independent of that read. */
  @Test
  fun agendaReadErrorDoesNotPreventOpeningForm() {
    repository.observeError = IllegalStateException("read failed")
    show()
    compose.onNodeWithTag(C.Tag.planning_error).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_add).performClick()
    compose.onNodeWithTag(C.Tag.appointment_screen).assertIsDisplayed()
  }

  /** Detail navigation retains the selected non-current day and the existing calendar entry. */
  @Test
  fun planningDetailBackKeepsSelectedDay() {
    val day = LocalDate.now().plusWeeks(1)
    repository.seed(
        "uid-patient",
        Appointment(
            "visit",
            "Future visit",
            day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant(),
            AppointmentType.CONSULTATION,
        ),
    )
    show()
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithText("Future visit").performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_screen).assertIsDisplayed()
    compose.onNodeWithText("Future visit").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextContains(PlanningDates.format(day))
    compose.onNodeWithText("Future visit").assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.PLANNING, nav.currentDestination?.route) }
  }

  /** The Overview card opens the same appointment, and Back returns directly to Overview. */
  @Test
  fun overviewDetailBackReturnsToOverview() {
    repository.seed(
        "uid-patient",
        Appointment(
            "overview-visit",
            "Overview visit",
            LocalDate.now().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
            AppointmentType.TREATMENT,
        ),
    )
    show(Route.OVERVIEW)
    compose.onNodeWithTag(C.Tag.overview_next_appointment).performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_screen).assertIsDisplayed()
    compose.onNodeWithText("Overview visit").assertIsDisplayed()
    compose.runOnIdle {
      assertEquals(
          "overview-visit",
          nav.currentBackStackEntry?.arguments?.getString(Screen.APPOINTMENT_ID),
      )
    }
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
  }

  /** A stale appointment link is recoverable, and repeated navigation does not stack details. */
  @Test
  fun unavailableDetailBackAndRepeatedOpen() {
    show()
    compose.runOnIdle {
      NavigationActions(nav).navigateTo(Screen.appointmentDetail("missing"))
      NavigationActions(nav).navigateTo(Screen.appointmentDetail("missing"))
    }
    compose.onNodeWithText("This appointment is unavailable.").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()
  }

  /** Returning from details retains a scrolled agenda instead of jumping to its first row. */
  @Test
  fun detailBackKeepsAgendaScroll() {
    val day = LocalDate.now()
    repository.seed(
        "uid-patient",
        *Array(25) { index ->
          Appointment(
              "visit-$index",
              "Agenda visit $index",
              day.atStartOfDay(ZoneId.systemDefault()).plusMinutes(index.toLong()).toInstant(),
              AppointmentType.OTHER,
          )
        },
    )
    show()
    compose.onNodeWithTag(C.Tag.planning_list).performScrollToNode(hasText("Agenda visit 20"))
    compose.onNodeWithText("Agenda visit 20").performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    compose.onNodeWithText("Agenda visit 20").assertIsDisplayed()
    compose.onNodeWithText("Agenda visit 0").assertDoesNotExist()
  }

  /** Cancel preserves storage; confirmation returns to the same selected Planning day. */
  @Test
  fun deleteFromPlanningKeepsSelectionAndRemovesRow() {
    val day = LocalDate.now().plusWeeks(1)
    val appointment =
        Appointment(
            "delete-visit",
            "Cancelled visit",
            day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant(),
            AppointmentType.CONSULTATION,
        )
    repository.seed("uid-patient", appointment)
    show()
    compose.onNodeWithTag(C.Tag.planning_next).performClick()
    compose.onNodeWithText(appointment.title).performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_delete_cancel).performClick()
    assertEquals(listOf(appointment), repository.appointments("uid-patient"))
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_delete_confirm).performClick()
    compose.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.planning_heading).assertTextContains(PlanningDates.format(day))
    compose.onNodeWithText(appointment.title).assertDoesNotExist()
    compose.runOnIdle { assertEquals(Screen.PLANNING, nav.currentDestination?.route) }
  }

  /** Overview immediately chooses the next remaining appointment after confirmed deletion. */
  @Test
  fun deleteFromOverviewRefreshesNextAppointment() {
    val time = LocalDate.now().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    repository.seed(
        "uid-patient",
        Appointment("first", "First visit", time, AppointmentType.CONSULTATION),
        Appointment("second", "Second visit", time.plusSeconds(3600), AppointmentType.OTHER),
    )
    show(Route.OVERVIEW)
    compose.onNodeWithTag(C.Tag.overview_next_appointment).performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_delete_confirm).performClick()
    compose.onNodeWithTag(C.Tag.overview_screen).assertIsDisplayed()
    compose.onNodeWithText("First visit").assertDoesNotExist()
    compose.onNodeWithText("Second visit").assertIsDisplayed()
    compose.runOnIdle { assertEquals(Screen.OVERVIEW, nav.currentDestination?.route) }
  }

  /** A paused detail cannot pop another destination; returning delivers completion once. */
  @Test
  fun delayedDeletionDoesNotPopAnotherScreen() {
    val time = LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
    repository.seed(
        "uid-patient",
        Appointment("visit", "Delayed visit", time, AppointmentType.OTHER),
    )
    repository.writeGate = kotlinx.coroutines.CompletableDeferred()
    show()
    compose.onNodeWithText("Delayed visit").performClick()
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_delete_confirm).performClick()
    compose.runOnIdle { NavigationActions(nav).navigateTo(Screen.PROFILE) }
    compose.runOnIdle { repository.writeGate!!.complete(Unit) }
    compose.runOnIdle { assertEquals(Screen.PROFILE, nav.currentDestination?.route) }
    compose.runOnIdle { NavigationActions(nav).goBack() }
    compose.onNodeWithTag(C.Tag.planning_screen).assertIsDisplayed()
    compose.onNodeWithText("Delayed visit").assertDoesNotExist()
    compose.runOnIdle { assertEquals(Screen.PLANNING, nav.currentDestination?.route) }
  }
}
