// Co-authored-by: Codex <noreply@openai.com>

package com.github.se.oncompanion.ui.appointment

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.se.oncompanion.model.appointment.*
import com.github.se.oncompanion.resources.C
import com.github.se.oncompanion.ui.LANDSCAPE_PHONE
import com.github.se.oncompanion.ui.auth.FakeAuthRepository
import com.github.se.oncompanion.ui.common.DeleteState
import java.time.*
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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

  /** English dates remain consistent on a French phone using a 12-hour clock. */
  @Test
  fun twelveHourClockAndFrenchLocale() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val previous = Settings.System.getString(context.contentResolver, Settings.System.TIME_12_24)
    try {
      Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
      compose.setContent {
        val configuration =
            Configuration(LocalConfiguration.current).apply { setLocale(Locale.FRENCH) }
        CompositionLocalProvider(LocalConfiguration provides configuration) {
          AppointmentDetailContent(
              AppointmentDetailUiState.Loaded(appointment),
              {},
              {},
              ZoneId.of("Europe/Zurich"),
          )
        }
      }
      compose.onNodeWithText("Fri 23 Oct 2026 · 1:00 AM").assertIsDisplayed()
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

  /** Appointment actions use the shared confirmation and preserve error/submission states. */
  @Test
  fun sharedDeleteDialogStatesAndCallbacks() {
    var deletion by mutableStateOf(DeleteState())
    var requests = 0
    var confirms = 0
    var cancels = 0
    compose.setContent {
      AppointmentDetailContent(
          AppointmentDetailUiState.Loaded(appointment, deletion),
          {},
          {},
          onDelete = {
            requests++
            deletion = DeleteState(isConfirming = true)
          },
          onConfirmDelete = { confirms++ },
          onCancelDelete = {
            cancels++
            deletion = DeleteState()
          },
      )
    }
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.onNodeWithTag(C.Tag.appointment_delete_dialog).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_delete_confirm).performClick()
    assertEquals(1, confirms)
    compose.onNodeWithTag(C.Tag.appointment_delete_cancel).performClick()
    assertEquals(1, cancels)
    compose.onNodeWithTag(C.Tag.appointment_delete_dialog).assertDoesNotExist()
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).performClick()
    compose.runOnIdle { deletion = deletion.copy(hasError = true) }
    compose.onNodeWithTag(C.Tag.appointment_delete_error).assertIsDisplayed()
    compose.runOnIdle { deletion = deletion.copy(isDeleting = true, hasError = false) }
    compose.onNodeWithTag(C.Tag.appointment_delete_confirm).assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.appointment_delete_cancel).assertIsNotEnabled()
    assertEquals(2, requests)
  }

  /** Failed reads and unavailable records never expose a destructive action. */
  @Test
  fun noDeleteActionWithoutLoadedAppointment() {
    var state by mutableStateOf<AppointmentDetailUiState>(AppointmentDetailUiState.Loading)
    compose.setContent { AppointmentDetailContent(state, {}, {}, onDelete = {}) }
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).assertDoesNotExist()
    compose.runOnIdle { state = AppointmentDetailUiState.Unavailable }
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).assertDoesNotExist()
    compose.runOnIdle { state = AppointmentDetailUiState.Error }
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).assertDoesNotExist()
  }

  /** The shared compact layout keeps deletion reachable after long notes. */
  @Test
  @Config(qualifiers = LANDSCAPE_PHONE)
  fun deletionRemainsReachableInLandscape() {
    compose.setContent {
      AppointmentDetailContent(
          AppointmentDetailUiState.Loaded(appointment.copy(notes = "Note ".repeat(100))),
          {},
          {},
          onDelete = {},
      )
    }
    compose
        .onNodeWithTag(C.Tag.appointment_detail_content)
        .performScrollToNode(hasTestTag(C.Tag.appointment_detail_delete_button))
    compose.onNodeWithTag(C.Tag.appointment_detail_delete_button).assertIsDisplayed()
  }

  /** A deletion finishing while paused is delivered when the same screen resumes. */
  @Test
  fun screenDeliversDeletionOnResume() {
    val repository = FakeAppointmentRepository()
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("patient") }
    repository.seed("uid-patient", appointment)
    val owner =
        object : LifecycleOwner {
          override val lifecycle = LifecycleRegistry(this)
        }
    lateinit var vm: AppointmentDetailViewModel
    var leaves = 0
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    compose.setContent {
      CompositionLocalProvider(LocalLifecycleOwner provides owner) {
        vm = remember { AppointmentDetailViewModel("visit", repository, auth) }
        AppointmentDetailScreen(vm, {}, onDeleted = { leaves++ })
      }
    }
    compose.onNodeWithText("Follow-up").assertIsDisplayed()
    compose.runOnIdle {
      owner.lifecycle.currentState = Lifecycle.State.STARTED
      vm.requestDelete()
      vm.confirmDelete()
    }
    compose.waitForIdle()
    assertEquals(0, leaves)
    assertTrue(repository.appointments("uid-patient").isEmpty())
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
    compose.waitForIdle()
    assertEquals(1, leaves)
    compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
  }
}
