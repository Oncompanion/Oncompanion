// Co-authored-by: ChatGPT <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.SavePhase
import com.github.se.oncompanion.ui.theme.OncompanionTheme
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reminder selection, dismissal and layout through the shared form and real Screen boundary. */
@RunWith(AndroidJUnit4::class)
class AppointmentReminderScreenTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  private val day = LocalDate.of(2026, 11, 20)

  @Composable
  private fun Form(state: AppointmentFormUiState, onChoice: (AppointmentReminder) -> Unit) {
    OncompanionTheme {
      AppointmentFormContent(
          state,
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          onReminderChange = onChoice,
      )
    }
  }

  /** Options expose a single-choice group, update the draft and dismiss immediately. */
  @Test
  fun everyChoiceUpdatesTheFormAndCancelOrBackKeepsIt() {
    var state by mutableStateOf(AppointmentFormUiState(day, ZoneId.of("Europe/Zurich")))
    compose.setContent { Form(state) { state = state.copy(reminder = it) } }
    val field = compose.onNodeWithTag(C.Tag.appointment_reminder)
    field.assertTextContains("1 hour before")
    for ((choice, label) in
        listOf(
            AppointmentReminder.NONE to "No reminder",
            AppointmentReminder.FIFTEEN_MINUTES to "15 minutes before",
            AppointmentReminder.ONE_HOUR to "1 hour before",
            AppointmentReminder.THREE_HOURS to "3 hours before",
            AppointmentReminder.ONE_DAY to "1 day before",
        )) {
      field.performScrollTo().performClick()
      compose.onNodeWithTag(C.Tag.appointmentReminder(state.reminder.name)).assertIsSelected()
      compose
          .onNodeWithTag(C.Tag.appointmentReminder(choice.name))
          .performScrollTo()
          .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
          .performClick()
      compose.onNodeWithTag(C.Tag.appointment_reminder_dialog).assertDoesNotExist()
      field.assertTextContains(label)
    }
    field.performClick()
    compose.onNodeWithTag(C.Tag.appointment_reminder_cancel).performClick()
    field.assertTextContains("1 day before")
  }

  /** A saving form cannot change its choice and closes an already open picker. */
  @Test
  fun savingClosesThePickerAndDisablesSelection() {
    var state by mutableStateOf(AppointmentFormUiState(day, ZoneId.of("Europe/Zurich")))
    var changes = 0
    compose.setContent { Form(state) { changes++ } }
    val field = compose.onNodeWithTag(C.Tag.appointment_reminder)
    field.performScrollTo().performClick()
    compose.runOnIdle { state = state.copy(save = SavePhase.SAVING) }
    compose.onNodeWithTag(C.Tag.appointment_reminder_dialog).assertDoesNotExist()
    field.assertIsNotEnabled().performClick()
    assertEquals(0, changes)
  }

  /** The picker stays open across saved-state restoration without silently selecting anything. */
  @Test
  fun openPickerSurvivesStateRestoration() {
    val restoration = StateRestorationTester(compose)
    restoration.setContent { Form(AppointmentFormUiState(day, ZoneId.of("Europe/Zurich"))) {} }
    compose.onNodeWithTag(C.Tag.appointment_reminder).performScrollTo().performClick()
    restoration.emulateSavedInstanceStateRestore()
    compose.onNodeWithTag(C.Tag.appointment_reminder_dialog).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointmentReminder("ONE_HOUR")).assertIsSelected()
  }

  /** A chosen draft survives activity recreation and reaches the repository through the Screen. */
  @Test
  fun chosenReminderSurvivesRotationAndSavesThroughScreen() {
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("patient") }
    val repository = FakeAppointmentRepository()
    val vm = AppointmentFormViewModel(day, ZoneId.of("Europe/Zurich"), repository, auth)
    compose.setContent { OncompanionTheme { AppointmentFormScreen(vm, {}, {}) } }
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Visit")
    compose.onNodeWithTag(C.Tag.appointment_reminder).performScrollTo().performClick()
    compose.onNodeWithTag(C.Tag.appointmentReminder("NONE")).performClick()
    compose.activityRule.scenario.recreate()
    compose.runOnUiThread {
      compose.activity.setContent { OncompanionTheme { AppointmentFormScreen(vm, {}, {}) } }
    }
    compose
        .onNodeWithTag(C.Tag.appointment_reminder)
        .performScrollTo()
        .assertTextContains("No reminder")
    compose.runOnIdle { vm.onTimeChange(LocalTime.NOON) }
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.waitUntil { repository.appointments("uid-patient").isNotEmpty() }
    assertEquals(AppointmentReminder.NONE, repository.appointments("uid-patient").single().reminder)
  }

  private fun checkLandscapePicker() {
    compose.setContent { Form(AppointmentFormUiState(day, ZoneId.of("Europe/Zurich"))) {} }
    compose.onNodeWithTag(C.Tag.appointment_reminder).performScrollTo().assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.appointment_reminder).performClick()
    for (choice in AppointmentReminder.entries) {
      compose
          .onNodeWithTag(C.Tag.appointmentReminder(choice.name))
          .performScrollTo()
          .assertIsFullyVisible()
    }
    compose.onNodeWithTag(C.Tag.appointment_reminder_cancel).assertIsFullyVisible()
    compose.onNodeWithTag(C.Tag.appointment_reminder_cancel).performClick()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsFullyVisible()
  }

  /** Every choice and both exit/save actions remain reachable on a landscape phone. */
  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun landscapeChoicesAndActionsAreReachable() = checkLandscapePicker()

  /** Short landscape windows scroll the options while keeping Cancel available. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscapeChoicesAndActionsAreReachable() = checkLandscapePicker()
}
