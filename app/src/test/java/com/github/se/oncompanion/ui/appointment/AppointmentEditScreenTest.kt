// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.*
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Edit states share the creation fields and remain usable above the keyboard. */
@RunWith(AndroidJUnit4::class)
class AppointmentEditScreenTest {
  @get:Rule val compose = createComposeRule()
  private val date = LocalDate.of(2026, 10, 27)

  private fun draft() =
      AppointmentFormUiState(
          date,
          ZoneId.of("Europe/Zurich"),
          title = "Visit",
          time = LocalTime.NOON,
          location = "Room 3",
          notes = "Bring documents",
          isEditing = true,
      )

  @Composable
  private fun content(state: AppointmentFormUiState, retry: () -> Unit = {}) {
    AppointmentFormContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onRetry = retry)
  }

  /** The form is hidden until its appointment is available, with actionable read feedback. */
  @Test
  fun loadStatesAndRetry() {
    var load by mutableStateOf(AppointmentFormLoad.LOADING)
    var retries = 0
    compose.setContent { content(draft().copy(load = load), { retries++ }) }
    compose.onNodeWithText("Edit appointment").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_form_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_title).assertDoesNotExist()
    for (value in
        listOf(
            AppointmentFormLoad.UNAVAILABLE,
            AppointmentFormLoad.NOT_SIGNED_IN,
            AppointmentFormLoad.FAILED,
        )) {
      compose.runOnIdle { load = value }
      compose.onNodeWithTag(C.Tag.appointment_title).assertDoesNotExist()
      compose.onNodeWithTag(C.Tag.appointment_form_retry).performClick()
    }
    assertEquals(3, retries)
    compose.runOnIdle { load = AppointmentFormLoad.READY }
    compose.onNodeWithTag(C.Tag.appointment_title).assertTextContains("Visit")
    compose.onNodeWithText("Save changes").performScrollTo().assertIsDisplayed()
  }

  /** The real Screen updates the same record and delivers completion only once. */
  @Test
  fun screenSavesExistingAppointmentOnce() {
    val repository = FakeAppointmentRepository()
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("patient") }
    repository.seed(
        "uid-patient",
        Appointment(
            "visit",
            "Visit",
            date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
            AppointmentType.OTHER,
        ),
    )
    var completions = 0
    compose.setContent {
      val vm = remember {
        AppointmentFormViewModel(
            date,
            repository = repository,
            authRepository = auth,
            appointmentId = "visit",
        )
      }
      AppointmentFormScreen(vm, {}, { completions++ })
    }
    compose.onNodeWithTag(C.Tag.appointment_title).performTextClearance()
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Corrected")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.runOnIdle {
      assertEquals(1, completions)
      assertEquals("Corrected", repository.appointments("uid-patient").single().title)
      assertEquals("visit", repository.appointments("uid-patient").single().id)
    }
  }

  /** Edit is an optional loaded-state action; no inactive Delete is introduced. */
  @Test
  fun detailEditActionOnlyForLoadedAppointment() {
    var state by mutableStateOf<AppointmentDetailUiState>(AppointmentDetailUiState.Loading)
    var edits = 0
    compose.setContent { AppointmentDetailContent(state, {}, {}, onEdit = { edits++ }) }
    compose.onNodeWithTag(C.Tag.appointment_detail_edit_button).assertDoesNotExist()
    compose.runOnIdle {
      state =
          AppointmentDetailUiState.Loaded(
              Appointment("id", "Visit", Instant.now(), AppointmentType.OTHER)
          )
    }
    compose.onNodeWithTag(C.Tag.appointment_detail_edit_button).performClick()
    assertEquals(1, edits)
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).assertDoesNotExist()
  }

  /** Portrait edit fields and Save stay above the keyboard. */
  @Test
  fun editingAbovePortraitKeyboard() {
    compose.setContent { content(draft()) }
    compose.showKeyboard(700)
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().assertIsAboveKeyboard(700)
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsAboveKeyboard(700)
  }

  /** Compact landscape editing keeps input and Save reachable by scrolling. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun editingAboveLandscapeKeyboard() {
    compose.setContent { content(draft()) }
    compose.showKeyboard(280)
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().assertIsAboveKeyboard(280)
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsAboveKeyboard(280)
  }

  /** Compact read feedback keeps Back reachable rather than trapping the patient. */
  @Test
  @Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun compactReadFailureKeepsBackAvailable() {
    var backs = 0
    compose.setContent {
      AppointmentFormContent(
          draft().copy(load = AppointmentFormLoad.FAILED),
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          {},
          { backs++ },
          onRetry = {},
      )
    }
    compose.onNodeWithTag(C.Tag.appointment_back).performScrollTo().performClick()
    assertEquals(1, backs)
  }
}
