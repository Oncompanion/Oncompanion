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

/** Form states and confirmed manual appointment creation. */
@RunWith(AndroidJUnit4::class)
class AppointmentFormScreenTest {
  @get:Rule val compose = createComposeRule()
  private val day = LocalDate.of(2026, 10, 15)
  private val zone = ZoneId.of("Europe/Zurich")

  private fun state() = AppointmentFormUiState(day, zone)

  private fun show(
      initial: AppointmentFormUiState,
      saved: () -> Unit = {},
      back: () -> Unit = {},
      dateChanged: (LocalDate) -> Unit = {},
      timeChanged: (LocalTime) -> Unit = {},
      typeChanged: (AppointmentType) -> Unit = {},
  ) {
    compose.setContent {
      AppointmentFormContent(
          initial,
          {},
          dateChanged,
          timeChanged,
          {},
          typeChanged,
          {},
          {},
          saved,
          {},
          back,
      )
    }
  }

  /** Incomplete forms show their fields and cannot save. */
  @Test
  fun emptyFormShowsRequiredFieldsAndDisabledSave() {
    show(state())
    compose.onNodeWithTag(C.Tag.appointment_title).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_date).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_time).assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_notes).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
  }

  /** Valid forms dispatch Save and Back to their owners. */
  @Test
  fun filledFormAllowsSaveAndBack() {
    var saves = 0
    var backs = 0
    show(state().copy(title = "Visit", time = LocalTime.NOON), { saves++ }, { backs++ })
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsEnabled().performClick()
    compose.onNodeWithTag(C.Tag.appointment_back).performClick()
    assertEquals(1, saves)
    assertEquals(1, backs)
  }

  /** Saving freezes input and displays progress instead of accepting another write. */
  @Test
  fun savingDisablesInputsAndShowsProgress() {
    show(state().copy(title = "Visit", time = LocalTime.NOON, isSaving = true))
    compose.onNodeWithTag(C.Tag.appointment_title).assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
    compose.onNodeWithTag(C.Tag.appointment_saving).assertIsDisplayed()
  }

  /** Storage errors are distinguished from connectivity and leave the entered draft visible. */
  @Test
  fun saveErrorKeepsDraftAndShowsMessage() {
    show(
        state()
            .copy(title = "Visit", time = LocalTime.NOON, error = AppointmentFormError.SAVE_FAILED)
    )
    compose.onNodeWithText("Couldn't save your appointment. Please try again.").assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_title).assertTextContains("Visit")
  }

  /** Overlong manual locations remain visible with an explanation. */
  @Test
  fun invalidLocationShowsItsLimitAndPreventsSave() {
    show(state().copy(title = "Visit", time = LocalTime.NOON, location = "a".repeat(201)))
    compose.onNodeWithText("Use at most 200 characters").performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsNotEnabled()
  }

  /** Padding around stored-limit text does not show a contradictory error. */
  @Test
  fun trimmedLimitsKeepSaveEnabledWithoutLengthErrors() {
    show(
        state()
            .copy(
                title = " " + "t".repeat(100) + " ",
                time = LocalTime.NOON,
                location = " " + "l".repeat(200) + " ",
                notes = " " + "n".repeat(1000) + " ",
            )
    )
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsEnabled()
    for (limit in listOf(100, 200, 1000)) compose
        .onNodeWithText("Use at most $limit characters")
        .assertDoesNotExist()
  }

  /** Limit messages select the correct English singular and plural forms. */
  @Test
  fun characterLimitMessagesUsePluralResources() {
    compose.setContent {
      androidx.compose.material3.Text(
          androidx.compose.ui.res.pluralStringResource(
              com.github.se.oncompanion.R.plurals.appointment_text_limit,
              1,
              1,
          )
      )
      androidx.compose.material3.Text(
          androidx.compose.ui.res.pluralStringResource(
              com.github.se.oncompanion.R.plurals.appointment_text_limit,
              2,
              2,
          )
      )
    }
    compose.onNodeWithText("Use at most 1 character").assertExists()
    compose.onNodeWithText("Use at most 2 characters").assertExists()
  }

  /** The real Screen records optional location text only after confirmation. */
  @Test
  fun realScreenSavesManualLocationOnce() {
    val auth = FakeAuthRepository()
    runTest { auth.signInWithGoogle("patient") }
    val repo = FakeAppointmentRepository()
    val vm = AppointmentFormViewModel(day, zone, repo, auth)
    var saves = 0
    compose.setContent { AppointmentFormScreen(vm, {}, { saves++ }) }
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Consultation")
    compose.runOnIdle { vm.onTimeChange(LocalTime.of(10, 30)) }
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().performTextInput("Room 12")
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().performClick()
    compose.waitForIdle()
    assertEquals("Room 12", repo.appointments("uid-patient").single().location)
    assertEquals(1, saves)
    compose.runOnIdle { vm.save() }
    compose.waitForIdle()
    assertEquals(1, saves)
  }

  /** Picker cancellation does not save and both dialogs expose confirmation. */
  @Test
  fun dateAndTimePickersCanConfirmAndCancel() {
    val dates = mutableListOf<LocalDate>()
    val times = mutableListOf<LocalTime>()
    show(
        state().copy(time = LocalTime.of(9, 15)),
        dateChanged = { dates.add(it) },
        timeChanged = { times.add(it) },
    )
    for (tag in listOf(C.Tag.appointment_date, C.Tag.appointment_time)) {
      compose.onNodeWithTag(tag).performScrollTo().performClick()
      compose.onNodeWithTag(C.Tag.appointment_cancel).performClick()
      assertTrue(dates.isEmpty())
      assertTrue(times.isEmpty())
    }
    for (tag in listOf(C.Tag.appointment_date, C.Tag.appointment_time)) {
      compose.onNodeWithTag(tag).performClick()
      compose.onNodeWithTag(C.Tag.appointment_confirm).performClick()
    }
    assertEquals(listOf(day), dates)
    assertEquals(listOf(LocalTime.of(9, 15)), times)
  }

  /** Categories are all available, without changing appointment instructions. */
  @Test
  fun allCategoryChoicesCanBeSelected() {
    val selected = mutableListOf<AppointmentType>()
    show(state(), typeChanged = { selected.add(it) })
    AppointmentType.entries.forEach {
      compose.onNodeWithTag(C.Tag.appointmentType(it.name)).performScrollTo().performClick()
    }
    assertEquals(AppointmentType.entries, selected)
  }

  /** Nonexistent and repeated local times show why the form cannot yet save. */
  @Test
  fun clockGapShowsCorrectionMessage() {
    show(
        state().copy(title = "Visit", date = LocalDate.of(2026, 3, 29), time = LocalTime.of(2, 30))
    )
    compose
        .onNodeWithText("This time does not exist on this date. Choose another time.")
        .performScrollTo()
        .assertIsDisplayed()
  }

  /** Each repeated-time occurrence is an explicit choice. */
  @Test
  fun repeatedTimeShowsBothOffsets() {
    show(
        state().copy(title = "Visit", date = LocalDate.of(2026, 10, 25), time = LocalTime.of(2, 30))
    )
    compose.onNodeWithText("UTC +02:00").performScrollTo().performClick()
    compose.onNodeWithText("UTC +01:00").performScrollTo().performClick()
  }

  /** Compact windows keep both header and Save reachable by scrolling. */
  @Test
  @org.robolectric.annotation.Config(qualifiers = "w640dp-h360dp")
  fun compactWindowAllowsScrollingBetweenBackAndSave() {
    show(state().copy(title = "Visit", time = LocalTime.NOON))
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsDisplayed()
    compose.onNodeWithTag(C.Tag.appointment_back).performScrollTo().assertIsDisplayed()
  }

  /** A missing title is explained after editing, instead of only coloring its outline. */
  @Test
  fun missingTitleShowsSpecificError() {
    show(state().copy(titleTouched = true))
    compose.onNodeWithText("Please enter a title").assertIsDisplayed()
  }

  /**
   * Default Firebase dependencies fail gracefully before Firebase is initialized in Robolectric.
   */
  @Test
  fun defaultViewModelCanShowFormWithoutFirebaseInitialization() {
    val vm = AppointmentFormViewModel(day)
    compose.setContent { AppointmentFormScreen(vm, {}, {}) }
    compose.onNodeWithTag(C.Tag.appointment_title).performTextInput("Visit")
    compose.runOnIdle {
      vm.onTimeChange(LocalTime.NOON)
      vm.save()
    }
    compose.onNodeWithText("Sign in again to save your appointment.").assertIsDisplayed()
  }

  /** Portrait scrolling keeps manual location and Save above the reported keyboard. */
  @Test
  fun fieldsAndSaveRemainAbovePortraitKeyboard() {
    show(state().copy(title = "Visit", time = LocalTime.NOON))
    compose.showKeyboard(700)
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().assertIsAboveKeyboard(700)
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().assertIsAboveKeyboard(700)
  }

  /** A small landscape viewport keeps the field and Save fully visible through scrolling. */
  @Test
  @org.robolectric.annotation.Config(qualifiers = SMALL_LANDSCAPE_PHONE)
  fun smallLandscapeKeyboardKeepsFieldsAndSaveReachable() {
    show(state().copy(title = "Visit", time = LocalTime.NOON))
    compose.showKeyboard(280)
    compose.onNodeWithTag(C.Tag.appointment_location).performScrollTo().apply {
      assertIsAboveKeyboard(280)
      assertIsFullyVisible()
    }
    compose.onNodeWithTag(C.Tag.appointment_save).performScrollTo().apply {
      assertIsAboveKeyboard(280)
      assertIsFullyVisible()
    }
  }
}
