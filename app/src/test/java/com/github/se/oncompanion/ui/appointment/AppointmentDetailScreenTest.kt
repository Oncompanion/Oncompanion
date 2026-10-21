// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Checks every detail state and the live Screen boundary without Firebase. */
@RunWith(AndroidJUnit4::class)
class AppointmentDetailScreenTest {
  @get:Rule val compose = createComposeRule()
  private val appointment =
      Appointment(
          "visit",
          "Follow-up",
          Instant.parse("2026-10-22T23:00:00Z"),
          AppointmentType.CONSULTATION,
          "Room 3",
          "Bring documents",
      )

  /** Only supplied optional fields appear, while long notes remain scrollable. */
  @Test
  fun recordedFieldsAndOptionalFields() {
    var state by
        mutableStateOf<AppointmentDetailUiState>(AppointmentDetailUiState.Loaded(appointment))
    compose.setContent { AppointmentDetailContent(state, {}, {}, ZoneId.of("Europe/Zurich")) }
    compose.onNodeWithText("Follow-up").assertIsDisplayed()
    compose.onNodeWithText("Consultation").assertIsDisplayed()
    compose.onNodeWithText("Room 3").assertIsDisplayed()
    compose.onNodeWithText("Bring documents").assertIsDisplayed()
    compose.onNodeWithText("Reminder").assertDoesNotExist()
    compose.runOnIdle {
      state =
          AppointmentDetailUiState.Loaded(
              appointment.copy(type = AppointmentType.TREATMENT, location = null, notes = null)
          )
    }
    compose.onNodeWithText("Treatment").assertIsDisplayed()
    compose.onNodeWithText("Room 3").assertDoesNotExist()
    compose.onNodeWithText("Notes").assertDoesNotExist()
    compose.runOnIdle {
      state =
          AppointmentDetailUiState.Loaded(
              appointment.copy(type = AppointmentType.OTHER, notes = "Long note ".repeat(100))
          )
    }
    compose.onNodeWithText("Other").assertIsDisplayed()
    compose.onNodeWithText("Long note ".repeat(100)).performScrollTo().assertIsDisplayed()
  }

  /** Loading, unavailable, error and retry remain distinct and Back always works. */
  @Test
  fun feedbackAndCallbacks() {
    var state by mutableStateOf<AppointmentDetailUiState>(AppointmentDetailUiState.Loading)
    var back = 0
    var retry = 0
    compose.setContent { AppointmentDetailContent(state, { back++ }, { retry++ }) }
    compose.onNodeWithTag(C.Tag.appointment_detail_loading).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    assertEquals(1, back)
    compose.runOnIdle { state = AppointmentDetailUiState.Unavailable }
    compose.onNodeWithText("This appointment is unavailable.").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_detail_retry).performClick()
    compose.runOnIdle { state = AppointmentDetailUiState.Error }
    compose.onNodeWithText("Couldn't load your appointment. Please try again.").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_detail_retry).performClick()
    assertEquals(2, retry)
  }

  /** Screen collection delivers repository changes through the actual ViewModel. */
  @Test
  fun screenObservesLiveAppointment() {
    val repository = FakeAppointmentRepository()
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("patient") }
    repository.seed("uid-patient", appointment)
    compose.setContent {
      val vm = remember { AppointmentDetailViewModel("visit", repository, auth) }
      AppointmentDetailScreen(vm, {})
    }
    compose.onNodeWithText("Follow-up").assertIsDisplayed()
    compose.runOnIdle { repository.seed("uid-patient", appointment.copy(title = "Updated")) }
    compose.onNodeWithText("Updated").assertIsDisplayed()
  }

  /** Calendar date includes the year and time follows the device clock preference and zone. */
  @Test
  fun dateAndTimeUseDisplayZoneAndClockPreference() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val previous = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
    try {
      Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
      compose.setContent {
        AppointmentDetailContent(
            AppointmentDetailUiState.Loaded(appointment),
            {},
            {},
            ZoneId.of("Europe/Zurich"),
        )
      }
      compose.onNodeWithText("Fri 23 Oct 2026 · 01:00").assertIsDisplayed()
    } finally {
      Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, previous)
    }
  }

  /** Optional fields vary independently and long text remains reachable at large font sizes. */
  @Test
  fun optionalFieldsAndLargeText() {
    var current by mutableStateOf(appointment.copy(location = null))
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        AppointmentDetailContent(AppointmentDetailUiState.Loaded(current), {}, {})
      }
    }
    compose.onNodeWithText("Location").assertDoesNotExist()
    compose.onNodeWithText("Bring documents").performScrollTo().assertIsDisplayed()
    compose.runOnIdle { current = appointment.copy(notes = null) }
    compose.onNodeWithText("Notes").assertDoesNotExist()
    compose.onNodeWithText("Room 3").performScrollTo().assertIsDisplayed()
  }
}
